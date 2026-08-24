package xyz.junproject.backend.search

import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.common.RequestLog
import xyz.junproject.backend.indexing.EmbeddingClient
import xyz.junproject.backend.indexing.EsClient

data class SearchHit(val path: String, val chunkNo: Int, val heading: String,
                     val snippet: String, val docKind: String, val score: Double)

/**
 * 하이브리드 검색 — BM25(nori) + kNN(dense)을 각각 실행해 수동 RRF(k=60)로 병합 (es-index.md D4).
 * 불변식: 검색은 llm에도, 임베딩에도 인질로 잡히지 않는다 — 어느 쪽이 죽어도 나머지로 응답한다.
 */
@Service
class SearchService(
    private val embedding: EmbeddingClient,
    private val es: EsClient,
    private val llm: LlmClient,
    private val requestLog: RequestLog,
    private val objectMapper: ObjectMapper,
) {
    private val defaultKinds = listOf("summary", "answer", "post")   // D3: question·index·readme 제외

    fun search(requestId: String, query: String, topic: String?,
               docKinds: List<String>?, size: Int): Map<String, Any?> {
        val rewrite = llm.rewrite(requestId, query)
        val bm25Query = if (rewrite.used)
            (listOf(query) + rewrite.keywords + rewrite.expanded).distinct().joinToString(" ")
        else query
        // 하드 필터는 명시 파라미터만. rewrite 제안(topic·doc_kind)은 필터로 쓰지 않는다 —
        // 실측(2026-08-24): "세마포어가 뭐야"에 모델이 doc_kind=question을 제안해 정리·정답이 전멸.
        // 제안은 로그로만 남겨 실측 데이터화 ([구현 검증] #7).
        val filterTopic = topic
        val filterKinds = docKinds?.takeIf { it.isNotEmpty() } ?: defaultKinds
        if (rewrite.used && (rewrite.topic != null || rewrite.docKind != null)) {
            requestLog.log(requestId,
                "rewrite hint (미적용): topic=${rewrite.topic} doc_kind=${rewrite.docKind}")
        }

        val bm25Ranking = bm25(bm25Query, filterTopic, filterKinds, size * 2)
        val (knnRanking, denseUsed) = try {
            knn(query, filterTopic, filterKinds, size * 2) to true
        } catch (error: Exception) {                    // 임베딩·kNN 장애 → BM25 단독 (B4)
            requestLog.log(requestId, "knn fallback: ${error.javaClass.simpleName}", "warning")
            emptyList<SearchHit>() to false
        }
        val merged = rrfMerge(listOf(bm25Ranking, knnRanking), size)
        requestLog.log(requestId,
            "search ok q=${query.take(40)} rewrite=${rewrite.used} dense=$denseUsed hits=${merged.size}")
        return mapOf("rewrite_used" to rewrite.used, "dense_used" to denseUsed,
            "results" to merged.map {
                mapOf("path" to it.path, "chunk_no" to it.chunkNo, "heading" to it.heading,
                      "snippet" to it.snippet, "doc_kind" to it.docKind, "score" to it.score)
            })
    }

    /** RRF 병합 — 청크 정체성은 path#chunk_no (es-index D1). 같은 청크는 점수 합산 (B1) */
    internal fun rrfMerge(rankings: List<List<SearchHit>>, size: Int, k: Int = 60): List<SearchHit> {
        val scores = LinkedHashMap<String, Pair<SearchHit, Double>>()
        for (ranking in rankings) {
            ranking.forEachIndexed { index, hit ->
                val key = "${hit.path}#${hit.chunkNo}"
                val rrfScore = 1.0 / (k + index + 1)
                val existing = scores[key]
                scores[key] = (existing?.first ?: hit) to ((existing?.second ?: 0.0) + rrfScore)
            }
        }
        return scores.values.sortedByDescending { it.second }
            .take(size).map { it.first.copy(score = it.second) }
    }

    private fun bm25(query: String, topic: String?, kinds: List<String>, size: Int): List<SearchHit> {
        val body = objectMapper.writeValueAsString(mapOf(
            "size" to size, "_source" to SOURCE_FIELDS,
            "query" to mapOf("bool" to mapOf(
                "must" to listOf(mapOf("multi_match" to mapOf(
                    "query" to query, "fields" to listOf("title^3", "heading^2", "content")))),
                "filter" to filters(topic, kinds),
            )),
        ))
        return parse(es.search(body))
    }

    private fun knn(query: String, topic: String?, kinds: List<String>, size: Int): List<SearchHit> {
        val vector = embedding.embedQuery(query)
        val body = objectMapper.writeValueAsString(mapOf(
            "size" to size, "_source" to SOURCE_FIELDS,
            "knn" to mapOf("field" to "dense", "query_vector" to vector,
                           "k" to size, "num_candidates" to size * 5,
                           "filter" to filters(topic, kinds)),
        ))
        return parse(es.search(body))
    }

    private fun filters(topic: String?, kinds: List<String>): List<Map<String, Any>> = buildList {
        add(mapOf("terms" to mapOf("doc_kind" to kinds)))
        topic?.let { add(mapOf("term" to mapOf("topic" to it))) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(response: Map<*, *>?): List<SearchHit> {
        val hits = ((response?.get("hits") as? Map<String, Any?>)?.get("hits") as? List<Map<String, Any?>>).orEmpty()
        return hits.map { hit ->
            val source = hit["_source"] as Map<String, Any?>
            SearchHit(
                path = source["path"] as String,
                chunkNo = (source["chunk_no"] as? Number)?.toInt() ?: 0,
                heading = source["heading"] as? String ?: "",
                snippet = (source["content"] as? String ?: "").take(200),
                docKind = source["doc_kind"] as? String ?: "",
                score = (hit["_score"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    }

    companion object {
        private val SOURCE_FIELDS = listOf("path", "chunk_no", "heading", "content", "doc_kind")
    }
}
