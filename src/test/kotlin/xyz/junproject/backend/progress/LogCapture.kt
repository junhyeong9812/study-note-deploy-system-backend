package xyz.junproject.backend.progress

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 모든 로거의 출력을 모은다 — 메시지 · 인자 · 예외 사슬의 문구까지 글자로.
 * 실제 [RequestLog] 를 쓴다(Redis 는 아무것도 하지 않는 가짜) — 요청 로그도 같은 로거로 나가 여기 잡힌다.
 */
class LogCapture : AutoCloseable {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }

    val requestLog = RequestLog(mockk<StringRedisTemplate>(relaxed = true))

    val lines: List<String>
        get() = appender.list.toList().map { event ->
            buildString {
                append(event.level).append(' ').append(event.formattedMessage)
                event.argumentArray?.forEach { append(' ').append(it) }
                var proxy: IThrowableProxy? = event.throwableProxy
                while (proxy != null) {
                    append(" | ").append(proxy.className).append(": ").append(proxy.message)
                    proxy.stackTraceElementProxyArray?.forEach { append(' ').append(it.steAsString) }
                    proxy = proxy.cause
                }
            }
        }

    override fun close() {
        root.detachAppender(appender)
        appender.stop()
    }
}
