package xyz.junproject.backend.shared.infra

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import xyz.junproject.backend.chat.usecase.EscalatePort
import xyz.junproject.backend.chat.usecase.EscalateRefusal
import xyz.junproject.backend.chat.usecase.EscalateResult
import java.time.Duration
import java.time.Instant

/** 에스컬레이션 ⓑ — PC의 Claude Code headless 브리지 호출(옛 통로 — 단추만). (chat-design)
 * PC 오프라인·미설정은 available=false → 호출부가 봉투 오류로 안내. 빈은 EscalateConfiguration 이 고른다 */
class ClaudeBridgeClient(
    private val objectMapper: tools.jackson.databind.ObjectMapper,
    private val bridgeUrl: String,
    private val bridgeSecret: String,
) : EscalatePort {
    override val available: Boolean get() = bridgeUrl.isNotBlank()
    /** 옛 통로로는 자동 넘김을 하지 않는다 — 단추만(명세 §20.3 ③) */
    override val autoEscalation: Boolean get() = false

    /** 읽기의 상한은 부를 때마다 남은 기한으로 — 기한이 지나면 읽기가 끊긴다 */
    private fun clientUntil(deadline: Instant): RestClient? {
        val remaining = Duration.between(Instant.now(), deadline)
        if (remaining.isNegative || remaining.isZero) return null
        return RestClient.builder().baseUrl(bridgeUrl)
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(3))
                setReadTimeout(remaining)
            }).build()
    }

    /** 답 또는 unavailable · timeout — 실패의 글(브리지의 몸이 섞일 수 있다)은 싣지 않는다 */
    override fun ask(requestId: String, prompt: String, deadline: Instant): EscalateResult {
        val client = clientUntil(deadline) ?: return EscalateResult.Refused(EscalateRefusal.TIMEOUT)
        // byte[] 본문 = Content-Length 명시 — Map 본문은 chunked 전송이 되는데
        // 브리지(파이썬 http.server)는 chunked 요청을 못 읽는다 (실측: 0바이트 파싱 오류)
        val payload = objectMapper.writeValueAsBytes(mapOf("prompt" to prompt))
        val answer = try {
            client.post().uri("/ask")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Request-Id", requestId)
                .header("X-Bridge-Secret", bridgeSecret)
                .body(payload)
                .retrieve().body(Map::class.java)?.get("answer") as? String
        } catch (_: Exception) {
            return EscalateResult.Refused(EscalateRefusal.UNAVAILABLE)
        }
        return if (answer.isNullOrBlank()) EscalateResult.Refused(EscalateRefusal.UNAVAILABLE) else EscalateResult.Answered(answer, false)
    }
}
