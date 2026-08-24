package xyz.junproject.backend.search

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import xyz.junproject.backend.common.RequestLog
import java.time.Duration

data class RewriteOutcome(
    val used: Boolean,
    val keywords: List<String> = emptyList(),
    val expanded: List<String> = emptyList(),
    val topic: String? = null,
    val docKind: String? = null,
)

/** llm wrapper /rewrite 호출 — 실패는 전부 "생략 폴백"(검색이 LLM에 인질로 잡히지 않는다). */
@Component
class LlmClient(private val requestLog: RequestLog) {
    private val client = RestClient.builder()
        .baseUrl(System.getenv("LLM_URL") ?: "http://llm:8000")
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(7))   // wrapper 총예산 5s + 여유
        })
        .build()

    @Suppress("UNCHECKED_CAST")
    fun rewrite(requestId: String, query: String): RewriteOutcome = try {
        val response = client.post().uri("/rewrite")
            .header("Content-Type", "application/json")
            .body(mapOf("request_id" to requestId, "query" to query))
            .retrieve().body(Map::class.java)
        val data = response?.get("data") as? Map<String, Any?>
        if (response?.get("success") == true && data != null) {
            val filters = data["filters"] as? Map<String, Any?> ?: emptyMap()
            RewriteOutcome(
                used = true,
                keywords = (data["keywords"] as? List<String>).orEmpty(),
                expanded = (data["expanded"] as? List<String>).orEmpty(),
                topic = filters["topic"] as? String,
                docKind = filters["doc_kind"] as? String,
            )
        } else fallback(requestId, "success=false")
    } catch (error: Exception) {
        fallback(requestId, error.javaClass.simpleName)
    }

    private fun fallback(requestId: String, reason: String): RewriteOutcome {
        requestLog.log(requestId, "rewrite fallback: $reason", "warning")
        return RewriteOutcome(used = false)
    }
}
