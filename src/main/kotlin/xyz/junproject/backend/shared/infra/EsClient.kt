package xyz.junproject.backend.shared.infra

import xyz.junproject.backend.search.usecase.SearchIndexPort

import xyz.junproject.backend.indexing.usecase.IndexStore

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/** ES 접근 단일 창구 — 색인·검색 공용 (B3: 타임아웃·인덱스명 정책을 한 곳에). 쓰기·읽기 모두 alias 경유. */
@Component
class EsClient : IndexStore, SearchIndexPort {
    private val baseUrl = System.getenv("ES_URL") ?: "http://elasticsearch:9200"
    val indexName = "study-v1"
    val alias = "study"

    private fun clientWith(readTimeout: Duration): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(3))
            setReadTimeout(readTimeout)
        })
        .build()

    private val queryClient = clientWith(Duration.ofSeconds(5))    // 검색·count — 사용자 경로
    private val bulkClient = clientWith(Duration.ofSeconds(60))    // bulk·delete_by_query — 색인 경로

    override fun ensureIndex() {
        val indexExists = try {
            queryClient.head().uri("/$indexName").retrieve().toBodilessEntity(); true
        } catch (_: Exception) { false }
        if (!indexExists) {
            bulkClient.put().uri("/$indexName").body(MAPPING_JSON.toByteArray())
                .header("Content-Type", "application/json; charset=utf-8")
                .retrieve().toBodilessEntity()
        }
        val aliasExists = try {                       // 인덱스만 있고 alias가 빠진 상태도 복구 (B3)
            queryClient.head().uri("/_alias/$alias").retrieve().toBodilessEntity(); true
        } catch (_: Exception) { false }
        if (!aliasExists) {
            bulkClient.post().uri("/_aliases")
                .body("""{"actions":[{"add":{"index":"$indexName","alias":"$alias"}}]}""".toByteArray())
                .header("Content-Type", "application/json; charset=utf-8").retrieve().toBodilessEntity()
        }
    }

    /** path의 기존 청크 전량 삭제 — 청크 수 감소 시 고아 방지 (D5-2) */
    override fun deleteByPath(path: String) {
        bulkClient.post().uri("/$alias/_delete_by_query?refresh=true")
            .body("""{"query":{"term":{"path":"${escape(path)}"}}}""".toByteArray())
            .header("Content-Type", "application/json; charset=utf-8").retrieve().toBodilessEntity()
    }

    /** full 색인 후 고아 정리 — commit_sha가 이번 값이 아닌 문서 삭제 (B7) */
    @Suppress("UNCHECKED_CAST")
    override fun deleteWhereShaNot(commitSha: String): Long {
        val response = bulkClient.post().uri("/$alias/_delete_by_query?refresh=true")
            .body("""{"query":{"bool":{"must_not":[{"term":{"commit_sha":"${escape(commitSha)}"}}]}}}""".toByteArray())
            .header("Content-Type", "application/json; charset=utf-8").retrieve().body(Map::class.java)
        return (response?.get("deleted") as? Number)?.toLong() ?: 0
    }

    override fun bulkUpsert(documents: List<Pair<String, String>>) {  // (docId, sourceJson)
        if (documents.isEmpty()) return
        val body = buildString {
            documents.forEach { (id, source) ->
                appendLine("""{"index":{"_index":"$alias","_id":"${escape(id)}"}}""")
                appendLine(source)
            }
        }
        val response = bulkClient.post().uri("/_bulk?refresh=true").body(body.toByteArray())
            .header("Content-Type", "application/x-ndjson; charset=utf-8").retrieve().body(Map::class.java)
        if (response?.get("errors") == true) {
            @Suppress("UNCHECKED_CAST")
            val firstError = (response["items"] as? List<Map<String, Map<String, Any?>>>)
                ?.firstNotNullOfOrNull { it["index"]?.get("error") }
            error("bulk 색인 부분 실패: $firstError")
        }
    }

    override fun countByPath(path: String): Long {
        val response = queryClient.post().uri("/$alias/_count")
            .body("""{"query":{"term":{"path":"${escape(path)}"}}}""".toByteArray())
            .header("Content-Type", "application/json; charset=utf-8").retrieve().body(Map::class.java)
        return (response?.get("count") as? Number)?.toLong() ?: -1
    }

    /** 검색 실행 — SearchService가 body(JSON)를 만들고 실행은 여기로 일원화 (B3) */
    override fun search(bodyJson: String): Map<*, *>? =
        queryClient.post().uri("/$alias/_search").body(bodyJson.toByteArray())
            .header("Content-Type", "application/json; charset=utf-8")
            .retrieve().body(Map::class.java)

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        // es-index.md D4 매핑 (sparse 제외)
        val MAPPING_JSON = """
        {"settings":{"analysis":{"analyzer":{"ko":{"type":"custom","tokenizer":"nori_tokenizer","filter":["nori_part_of_speech","lowercase"]}}}},
         "mappings":{"properties":{
           "path":{"type":"keyword"},"topic":{"type":"keyword"},"topic_path":{"type":"keyword"},
           "subject":{"type":"keyword"},"depth":{"type":"integer"},"form":{"type":"keyword"},
           "doc_kind":{"type":"keyword"},
           "title":{"type":"text","analyzer":"ko"},"heading":{"type":"text","analyzer":"ko"},
           "content":{"type":"text","analyzer":"ko"},"chunk_no":{"type":"integer"},
           "dense":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine"},
           "commit_sha":{"type":"keyword"},"updated_at":{"type":"date"}}}}
        """.trimIndent()
    }
}
