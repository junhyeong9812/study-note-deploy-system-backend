package xyz.junproject.backend.indexing

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** ES 저수준 클라이언트 — 인덱스 보장·bulk upsert·path 삭제·count (es-index.md D4·D5). */
@Component
class EsClient {
    private val client = RestClient.create(System.getenv("ES_URL") ?: "http://elasticsearch:9200")
    val indexName = "study-v1"
    val alias = "study"

    fun ensureIndex() {
        val exists = try {
            client.head().uri("/$indexName").retrieve().toBodilessEntity()
            true
        } catch (_: Exception) { false }
        if (exists) return
        client.put().uri("/$indexName").body(MAPPING_JSON).header("Content-Type", "application/json")
            .retrieve().toBodilessEntity()
        client.post().uri("/_aliases")
            .body("""{"actions":[{"add":{"index":"$indexName","alias":"$alias"}}]}""")
            .header("Content-Type", "application/json").retrieve().toBodilessEntity()
    }

    /** path의 기존 청크 전량 삭제 — 청크 수 감소 시 고아 방지 (D5-2) */
    fun deleteByPath(path: String) {
        client.post().uri("/$indexName/_delete_by_query?refresh=true")
            .body("""{"query":{"term":{"path":"${'$'}{escape(path)}"}}}""".replace("${'$'}{escape(path)}", escape(path)))
            .header("Content-Type", "application/json").retrieve().toBodilessEntity()
    }

    fun bulkUpsert(documents: List<Pair<String, String>>) {  // (docId, sourceJson)
        if (documents.isEmpty()) return
        val body = buildString {
            documents.forEach { (id, source) ->
                appendLine("""{"index":{"_index":"$indexName","_id":"${escape(id)}"}}""")
                appendLine(source)
            }
        }
        val response = client.post().uri("/_bulk?refresh=true").body(body)
            .header("Content-Type", "application/x-ndjson").retrieve().body(Map::class.java)
        if (response?.get("errors") == true) error("bulk 색인 부분 실패: ${'$'}response")
    }

    fun countByPath(path: String): Long {
        val response = client.post().uri("/$indexName/_count")
            .body("""{"query":{"term":{"path":"${escape(path)}"}}}""")
            .header("Content-Type", "application/json").retrieve().body(Map::class.java)
        return (response?.get("count") as? Number)?.toLong() ?: -1
    }

    fun totalCount(): Long {
        val response = client.get().uri("/$indexName/_count").retrieve().body(Map::class.java)
        return (response?.get("count") as? Number)?.toLong() ?: -1
    }

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
