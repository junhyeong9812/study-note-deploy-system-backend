package xyz.junproject.backend.infra

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

    /** 인입 requestId 검증 — 규약 포맷(콜론 구분)을 깨는 값이면 재발행 (B11) */
    fun acceptOrIssue(incoming: String?): String =
        if (incoming != null && incoming.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) incoming
        else newRequestId()

    fun formatLine(requestId: String, message: String) = "$requestId:$serverName:$message"

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
