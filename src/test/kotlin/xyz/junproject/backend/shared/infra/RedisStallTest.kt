package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.progress.usecase.FakeProgressRecords
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.quiz.FakeNoteSource
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.usecase.PartDeletion
import xyz.junproject.backend.quiz.usecase.QuizService
import xyz.junproject.backend.quiz.usecase.RecordDeletion
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 명세 20 — Redis 가 응답 없이 멈춰도 요청이 오래 붙잡히지 않는다. 연결은 받지만 한 글자도 답하지 않는 가짜 Redis 에
 * **앱의 실제 설정**(`application.yml` 을 읽은 Redis 자동 설정)으로 붙인다 — 설정이 없으면 명령 하나가 Lettuce 기본값인 60초를 기다린다.
 * 실제 시간에 기대므로 느리다(초록에서 합쳐 십여 초). 한계를 넘으면 그 자리에서 끊는다(assertTimeoutPreemptively).
 */
class RedisStallTest {
    /** 연결을 받아 쥐고만 있는 가짜 Redis — 읽지도 쓰지도 않는다 */
    private class SilentRedis : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val held = CopyOnWriteArrayList<Socket>()
        init {
            Thread.ofVirtual().name("silent-redis").start {
                while (!server.isClosed) {
                    try { held += server.accept() } catch (_: IOException) { break }
                }
            }
        }
        val port: Int get() = server.localPort
        override fun close() {
            server.close()
            held.forEach { runCatching { it.close() } }
        }
    }

    private val silentRedis = SilentRedis()

    @AfterEach
    fun close() = silentRedis.close()

    /** application.yml 을 읽고 Redis 자동 설정만 띄운다 — 주소만 가짜 Redis 로(LOG_REDIS_URL 은 그 yml 의 자리 표시) */
    private fun appRedis() = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration::class.java))
        .withPropertyValues("LOG_REDIS_URL=redis://127.0.0.1:${silentRedis.port}")

    @Test
    fun `앱 설정에 Redis 명령의 시간 제한이 있고 2초 이하다`() {
        appRedis().run { context ->
            assertNull(context.startupFailure, context.startupFailure?.toString())
            val timeout = context.getBean(DataRedisProperties::class.java).timeout
            assertNotNull(timeout, "spring.data.redis.timeout 이 없다 — Lettuce 기본 60초가 된다")
            assertTrue(timeout!! > Duration.ZERO && timeout <= Duration.ofSeconds(2), "timeout=$timeout")
        }
    }

    @Test
    fun `Redis 가 답하지 않아도 요청 로그 한 번은 5초 안에 돌아온다`() {
        appRedis().run { context ->
            val requestLog = RequestLog(context.getBean(StringRedisTemplate::class.java))
            assertTimeoutPreemptively(Duration.ofSeconds(5)) { requestLog.log("req-stall", "redis stall probe") }
        }
    }

    @Test
    fun `Redis 가 답하지 않아도 내 기록 지우기는 20초 안에 끝나고 퀴즈 기록은 지워짐 대화 이력은 실패로 답한다`() {
        appRedis().run { context ->
            val redis = context.getBean(StringRedisTemplate::class.java)
            val requestLog = RequestLog(redis)
            val clock = Clock.fixed(QuizTestBed.NOW, ZoneOffset.UTC)
            val records = FakeProgressRecords()
            val token = "K".repeat(43)
            records.createVisitor(VisitorToken.hash(token), QuizTestBed.TODAY, QuizTestBed.NOW)
            val quiz = QuizService(FakeNoteSource(), StudyProgressService(records, clock, requestLog), RedisChatHistory(redis), clock, requestLog)

            val result: RecordDeletion = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier {
                quiz.deleteMyRecords("req-stall", token, "123e4567-e89b-12d3-a456-426614174000")
            })

            assertEquals(PartDeletion.DELETED, result.quiz)
            assertEquals(PartDeletion.FAILED, result.chatHistory)
            assertEquals(0, records.visitorsByHash.size)
        }
    }
}
