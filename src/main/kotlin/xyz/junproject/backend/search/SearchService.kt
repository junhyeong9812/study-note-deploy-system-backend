package xyz.junproject.backend.search

import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.common.RequestLog
import xyz.junproject.backend.indexing.EmbeddingClient

data class SearchHit(val path: String, val heading: String, val snippet: String,
                     val docKind: String, val score: Double)

/**
 * 하이브리드 검색 — BM25(nori) + kNN(dense) 를 각각 실행해 수동 RRF(k=60)로 병합 (es-index.md D4).
 * RRF: 점수 스케일이 다른 두 랭킹을 "순위"만으로 합친다 — score = Σ 1/(k + rank).
 */
@Service
class SearchService(
    private val embedding: EmbeddingClient,
    private val llm: LlmClient,
    private val requestLog: RequestLog,
    private val objectMapper: ObjectMapper,
) {
    private val es = RestClient.create(System.getenv("ES_URL") ?: "http://elasticsearch:9200")
    private val defaultKinds = listOf("summary", "answer", "post")   // D3: question·index·readme 제외

    fun search(requestId: String, query: String, topic: String?, size: Int): Map<String, Any?> {
        val rewrite = llm.rewrite(requestId, query)
        val bm25Query = if (rewrite.used)
            (listOf(query) + rewrite.keywords + rewrite.expanded).joinToString(" ")
        else query
        val filterTopic = topic ?: rewrite.topic.takeIf { rewrite.used }

        val bm25Ranking = bm25(bm25Query, filterTopic, size * 2)
        val knnRanking = knn(query, filterTopic, size * 2)
        val merged = rrfMerge(listOf(bm25Ranking, knnRanking), size)
        requestLog.log(requestId,
            "search ok q=${query.take(40)} rewrite=${rewrite.used} hits=${merged.size}")
        return mapOf("rewrite_used" to rewrite.used, "results" to merged.map {
            mapOf("path" to it.path, "heading" to it.heading, "snippet" to it.snippet,
                  "doc_kind" to it.docKind, "score" to it.score)
        })
    }

    /** 두 랭킹을 RRF로 병합 — 같은 청크(_id 기준)는 점수 합산 */
    internal fun rrfMerge(rankings: List<List<SearchHit>>, size: Int, k: Int = 60): List<SearchHit> {
        val scores = LinkedHashMap<String, Pair<SearchHit, Double>>()
        for (ranking in rankings) {
            ranking.forEachIndexed { index, hit ->
                val key = "${hit.path}#${hit.heading}"
                val rrfScore = 1.0 / (k + index + 1)
                val existing = scores[key]
                scores[key] = (existing?.first ?: hit) to ((existing?.second ?: 0.0) + rrfScore)
            }
        }
        return scores.values.sortedByDescending { it.second }
            .take(size).map { it.first.copy(score = it.second) }
    }

    private fun bm25(query: String, topic: String?, size: Int): List<SearchHit> {
        val body = objectMapper.writeValueAsString(mapOf(
            "size" to size, "_source" to listOf("path", "heading", "content", "doc_kind"),
            "query" to mapOf("bool" to buildMap<String, Any> {
                put("must", listOf(mapOf("multi_match" to mapOf(
                    "query" to query, "fields" to listOf("title^3", "heading^2", "content")))))
                put("filter", filters(topic))
            }),
        ))
        return execute(body)
    }

    private fun knn(query: String, topic: String?, size: Int): List<SearchHit> {
        val vector = embedding.embed(listOf(query)).firstOrNull() ?: return emptyList()
        val body = objectMapper.writeValueAsString(mapOf(
            "size" to size, "_source" to listOf("path", "heading", "content", "doc_kind"),
            "knn" to mapOf("field" to "dense", "query_vector" to vector,
                           "k" to size, "num_candidates" to size * 5,
                           "filter" to filters(topic)),
        ))
        return execute(body)
    }

    private fun filters(topic: String?): List<Map<String, Any>> = buildList {
        add(mapOf("terms" to mapOf("doc_kind" to defaultKinds)))
        topic?.let { add(mapOf("term" to mapOf("topic" to it))) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun execute(body: String): List<SearchHit> {
        val response = es.post().uri("/study/_search").body(body.toByteArray())
            .header("Content-Type", "application/json; charset=utf-8")
            .retrieve().body(Map::class.java) ?: return emptyList()
        val hits = ((response["hits"] as? Map<String, Any?>)?.get("hits") as? List<Map<String, Any?>>).orEmpty()
        return hits.map { hit ->
            val source = hit["_source"] as Map<String, Any?>
            SearchHit(
                path = source["path"] as String,
                heading = source["heading"] as? String ?: "",
                snippet = (source["content"] as? String ?: "").take(200),
                docKind = source["doc_kind"] as? String ?: "",
                score = (hit["_score"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    }
}
