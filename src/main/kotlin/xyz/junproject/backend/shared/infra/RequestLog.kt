package xyz.junproject.backend.shared.infra

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 로그 규약 구현 — `requestId:server-name:message` (루트 docs/logging.md 정본).
 * stdout(항상) + Redis Stream XADD(실패 무해 — 예외 전량 흡수, 요청을 인질로 잡지 않는다).
 * requestId는 backend가 발행하는 것이 규약 — 발행 유틸 포함.
 */
@Component
class RequestLog(private val redis: StringRedisTemplate) {
    private val serverLogger = LoggerFactory.getLogger("backend")
    private val serverName = System.getenv("SERVER_NAME") ?: "backend"
    private val stream = System.getenv("LOG_STREAM") ?: "logs"

    fun newRequestId(): String = "req-" + java.util.UUID.randomUUID().toString().take(12)

    /**
     * 인입 requestId 검증 — 규약 포맷(콜론 구분)을 깨는 값이면 재발행 (B11).
     * 정한 값을 요청 범위([REQUEST_ID_ATTRIBUTE])에 남긴다 — 처리하지 못한 예외의 로그도 같은 ID 를 쓰게(요청 밖에서 부르면 남기지 않는다)
     */
    fun acceptOrIssue(incoming: String?): String {
        val requestId = if (isRequestId(incoming)) incoming!! else newRequestId()
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()?.setAttribute(
            REQUEST_ID_ATTRIBUTE, requestId, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST)
        return requestId
    }

    fun formatLine(requestId: String, message: String) = "$requestId:$serverName:$message"

    companion object {
        /** 추적 ID 의 꼴(통신 규약 §1.2) — 로그 한 줄의 구분자 `:` · 줄바꿈이 들 수 없다. 이 꼴을 쓰는 곳은 모두 이것을 쓴다 */
        private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        /** 그 요청에서 정한 추적 ID 를 남기는 요청 속성의 이름 */
        const val REQUEST_ID_ATTRIBUTE = "xyz.junproject.backend.requestId"
        /** 다른 서비스에 추적 ID 를 싣는 머리글 */
        const val REQUEST_ID_HEADER = "X-Request-Id"

        fun isRequestId(value: String?): Boolean = value != null && REQUEST_ID.matches(value)
    }

    fun log(requestId: String, message: String, level: String = "info") {
        val line = formatLine(requestId, message)
        when (level) {
            "error" -> serverLogger.error(line)
            "warning" -> serverLogger.warn(line)
            else -> serverLogger.info(line)
        }
        try {
            val record = StreamRecords.mapBacked<String, String, String>(
                mapOf("level" to level, "line" to line)
            ).withStreamKey(stream)
            // MAXLEN ~10000 — 소비자 부재 기간에도 Redis 메모리 상한 유지 (규약: 정본은 stdout)
            redis.opsForStream<String, String>().add(
                record,
                org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions.maxlen(10_000)
                    .approximateTrimming(true),
            )
        } catch (_: Exception) {
            // 중앙 큐는 best-effort — 정본은 stdout (docs/logging.md)
        }
    }
}
