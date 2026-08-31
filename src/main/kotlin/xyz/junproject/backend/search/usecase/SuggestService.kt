package xyz.junproject.backend.search.usecase

import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 자동완성 — ES 단독(저지연: rewrite·kNN 미사용). 제목/헤딩/내용 일치, path별 1건(collapse),
 * 일치 부분은 ⟦m⟧…⟦/m⟧ 마커(front가 이스케이프 후 강조 렌더 — HTML 주입 원천 차단). (#22)
 */
@Service
class SuggestService(
    private val es: SearchIndexPort,
    private val requestLog: RequestLog,
    private val objectMapper: ObjectMapper,
) {
    data class SuggestItem(val path: String, val title: String, val docKind: String, val snippet: String)

    fun suggest(requestId: String, query: String, size: Int = 8): List<SuggestItem> {
        val body = objectMapper.writeValueAsString(mapOf(
            "size" to size,
            "_source" to listOf("path", "title", "doc_kind"),
            "query" to mapOf("bool" to mapOf(
                "must" to listOf(mapOf("multi_match" to mapOf(
                    "query" to query, "fields" to listOf("title^3", "heading^2", "content")))),
                "filter" to listOf(mapOf("terms" to mapOf(
                    "doc_kind" to listOf("summary", "answer", "post")))),
            )),
            "collapse" to mapOf("field" to "path"),          // 문서(path)당 최고 청크 1건
            "highlight" to mapOf(
                "pre_tags" to listOf("⟦m⟧"), "post_tags" to listOf("⟦/m⟧"),
                "fields" to mapOf(
                    "title" to mapOf("number_of_fragments" to 0),
                    "content" to mapOf("fragment_size" to 90, "number_of_fragments" to 1),
                ),
            ),
        ))
        @Suppress("UNCHECKED_CAST")
        val hits = ((es.search(body)?.get("hits") as? Map<String, Any?>)
            ?.get("hits") as? List<Map<String, Any?>>).orEmpty()
        val items = hits.map { hit ->
            val source = hit["_source"] as Map<String, Any?>
            val highlight = hit["highlight"] as? Map<String, List<String>> ?: emptyMap()
            SuggestItem(
                path = source["path"] as String,
                title = highlight["title"]?.firstOrNull() ?: (source["title"] as? String ?: ""),
                docKind = source["doc_kind"] as? String ?: "",
                snippet = highlight["content"]?.firstOrNull() ?: "",
            )
        }
        requestLog.log(requestId, "suggest q=${query.take(30)} hits=${items.size}")
        return items
    }
}
