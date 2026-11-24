package xyz.junproject.backend.progress.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import xyz.junproject.backend.progress.ProgressConfiguration
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * 명세 15 — DB 가 없을 때 퀴즈 요청이 앱의 다른 요청을 붙잡지 않는다: 표 준비를 보는 자리에서 잠금을 쥔 채 DB 를 기다리지 않고,
 * DB 에 닿지 못한 직후(짧은 쉼)의 요청은 풀을 기다리지 않고 곧바로 실패한다. 쉼이 지나면 다시 닿아 본다.
 * 운영과 같은 풀은 [ProgressConfiguration] 이 만든 그대로 쓴다(아무도 듣지 않는 주소).
 */
class ProgressStoreUnavailableTest {
    private val unreachableUrl = "jdbc:postgresql://127.0.0.1:1/study"
    private val hash = "h".repeat(64)

    /** 연결을 줄 때마다 세고 실패하는 DB — 풀 대기 없이 곧바로 거절한다 */
    private class CountingUnreachableDataSource : AbstractDataSource() {
        @Volatile var connectionAttempts = 0
        override fun getConnection(): Connection { connectionAttempts++; throw SQLException("Connection refused", "08001") }
        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun submission() = AnswerSubmission(UUID.randomUUID(), "cs/os/process", 1, "답", "a".repeat(40), "b".repeat(64))

    @Test
    fun `DB 에 닿지 못하는 동안 저장소를 부르는 가상 스레드들이 무관한 가상 스레드를 붙잡지 않는다`() {
        val configuration = ProgressConfiguration()
        val jdbc = configuration.studyJdbc(unreachableUrl, "study", "unused")
        try {
            val store = configuration.progressStore(jdbc)
            // 가상 스레드의 실행 자리 수(테스트 JVM 은 build.gradle.kts 가 2 로 준다) — 그보다 많은 호출이 저장소를 부른다
            val parallelism = System.getProperty("jdk.virtualThreadScheduler.parallelism")?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()
            val callers = (0 until parallelism + 2).map {
                Thread.ofVirtual().start { runCatching { store.findVisitor(hash) } }
            }
            Thread.sleep(300)                                            // 호출들이 풀을 기다리기 시작하게
            val started = System.nanoTime()
            val unrelated = Thread.ofVirtual().start { }
            assertTrue(unrelated.join(Duration.ofSeconds(60)), "무관한 가상 스레드가 60초 안에 끝나지 않았다")
            val waited = Duration.ofNanos(System.nanoTime() - started)
            callers.forEach { assertTrue(it.join(Duration.ofSeconds(60)), "저장소 호출이 60초 안에 끝나지 않았다") }
            assertTrue(waited < Duration.ofSeconds(1), "무관한 가상 스레드가 ${waited.toMillis()}ms 밀렸다(parallelism=$parallelism)")
        } finally {
            configuration.closePool()
        }
    }

    @Test
    fun `운영 풀에서 닿지 못한 직후의 다음 호출은 풀을 기다리지 않고 곧바로 실패한다`() {
        val configuration = ProgressConfiguration()
        val jdbc = configuration.studyJdbc(unreachableUrl, "study", "unused")
        try {
            val store = configuration.progressStore(jdbc)
            assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }   // 풀 대기(2초) 뒤 실패
            val started = System.nanoTime()
            assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
            val waited = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(waited < Duration.ofSeconds(1), "닿지 못한 직후의 호출이 ${waited.toMillis()}ms 기다렸다(풀 대기 2초)")
        } finally {
            configuration.closePool()
        }
    }

    @Test
    fun `쉼 동안에는 DB 에 닿아 보지 않고 저장소 오류로 실패하고 쉼이 지나면 다시 닿아 본다`() {
        val dataSource = CountingUnreachableDataSource()
        val clock = MovableClock(Instant.parse("2026-11-25T01:00:00Z"))
        val store = ProgressStore(JdbcTemplate(dataSource), clock, Duration.ofSeconds(5))

        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        val afterFirstFailure = dataSource.connectionAttempts
        assertTrue(afterFirstFailure >= 1, "처음 호출이 DB 에 닿아 보지 않았다")

        clock.now = clock.now.plusMillis(4_999)
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertThrows(DataAccessException::class.java) { store.recordAttempt(1, submission(), clock.now) }
        assertThrows(DataAccessException::class.java) { store.attemptsOf(1) }
        assertEquals(afterFirstFailure, dataSource.connectionAttempts, "쉼 동안에 DB 에 다시 닿아 봤다")

        clock.now = clock.now.plusMillis(2)                              // 처음 실패부터 5.001초 — 쉼이 지남
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertTrue(dataSource.connectionAttempts > afterFirstFailure, "쉼이 지났는데 다시 닿아 보지 않았다")
    }
}
