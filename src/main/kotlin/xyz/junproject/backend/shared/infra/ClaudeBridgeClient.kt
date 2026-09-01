package xyz.junproject.backend.shared.infra

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import xyz.junproject.backend.chat.usecase.EscalatePort
import java.time.Duration

/** 에스컬레이션 ⓑ — PC의 Claude Code headless 브리지 호출. (chat-design)
 * PC 오프라인·미설정은 available=false → 호출부가 봉투 오류로 안내 */
@Component
class ClaudeBridgeClient : EscalatePort {
    private val bridgeUrl = System.getenv("CLAUDE_BRIDGE_URL") ?: ""
    override val available: Boolean get() = bridgeUrl.isNotBlank()

    private val client by lazy {
        RestClient.builder().baseUrl(bridgeUrl)
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(3))
                setReadTimeout(Duration.ofMinutes(3))   // claude -p 는 느리다
            }).build()
    }

    @Suppress("UNCHECKED_CAST")
    override fun ask(requestId: String, prompt: String): String {
        val response = client.post().uri("/ask")
            .header("Content-Type", "application/json")
            .header("X-Request-Id", requestId)
            .body(mapOf("prompt" to prompt))
            .retrieve().body(Map::class.java) ?: error("bridge: empty response")
        return response["answer"] as? String ?: error("bridge: no answer field")
    }
}
