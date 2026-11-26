package xyz.junproject.backend.shared.infra

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import xyz.junproject.backend.chat.usecase.EscalatePort
import java.time.Duration

/** 에스컬레이션 ⓑ — PC의 Claude Code headless 브리지 호출. (chat-design)
 * PC 오프라인·미설정은 available=false → 호출부가 봉투 오류로 안내 */
@Component
class ClaudeBridgeClient(private val objectMapper: tools.jackson.databind.ObjectMapper) : EscalatePort {
    private val bridgeUrl = System.getenv("CLAUDE_BRIDGE_URL") ?: ""
    private val bridgeSecret = System.getenv("BRIDGE_SECRET") ?: ""
    override val available: Boolean get() = bridgeUrl.isNotBlank()
    /** 옛 통로로는 자동 넘김을 하지 않는다 — 단추만(명세 §20.3 ③) */
    override val autoEscalation: Boolean get() = false

    private val client by lazy {
        RestClient.builder().baseUrl(bridgeUrl)
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(3))
                setReadTimeout(Duration.ofMinutes(3))   // claude -p 는 느리다
            }).build()
    }

    override fun ask(requestId: String, prompt: String, deadline: java.time.Instant): xyz.junproject.backend.chat.usecase.EscalateResult = TODO("5a")

    @Suppress("UNCHECKED_CAST")
    private fun askOld(requestId: String, prompt: String): String {
        // byte[] 본문 = Content-Length 명시 — Map 본문은 chunked 전송이 되는데
        // 브리지(파이썬 http.server)는 chunked 요청을 못 읽는다 (실측: 0바이트 파싱 오류)
        val payload = objectMapper.writeValueAsBytes(mapOf("prompt" to prompt))
        val response = client.post().uri("/ask")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Request-Id", requestId)
            .header("X-Bridge-Secret", bridgeSecret)
            .body(payload)
            .retrieve().body(Map::class.java) ?: error("bridge: empty response")
        return response["answer"] as? String ?: error("bridge: no answer field")
    }
}
