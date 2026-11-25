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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 명세 15 — DB 가 없을 때 퀴즈 요청이 앱의 다른 요청을 붙잡지 않는다: 표 준비를 보는 자리에서 잠금을 쥔 채 DB 를 기다리지 않고,
 * DB 에 닿지 못한 직후(짧은 쉼)의 요청은 풀을 기다리지 않고 곧바로 실패한다. 쉼이 지나면 다시 닿아 본다.
 * 운영과 같은 풀은 [ProgressConfiguration] 이 만든 그대로 쓴다(아무도 듣지 않는 주소).
 */
class ProgressStoreUnavailableTest {
    private val unreachableUrl = "jdbc:postgresql://127.0.0.1:1/study"
    private val hash = "h".repeat(64)

    /** 연결을 줄 때마다 세고 [failure] 로 실패하는 DB — 풀 대기 없이 곧바로 거절한다 */
    private class CountingFailingDataSource(private val failure: () -> SQLException = ::unreachable) : AbstractDataSource() {
        @Volatile var connectionAttempts = 0
        override fun getConnection(): Connection { connectionAttempts++; throw failure() }
        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }

    /** 흐른 시간(나노초) — 테스트가 움직인다 */
    private class ElapsedTime(var nanos: Long = 1_000_000_000L) { fun read(): Long = nanos; fun advance(duration: Duration) { nanos += duration.toNanos() } }

    private fun submission() = AnswerSubmission(UUID.randomUUID(), "cs/os/process", 1, "답", "a".repeat(40), "b".repeat(64))

    companion object {
        /** HikariCP 가 연결을 만들지 못해 낸 꼴(실측: 닿지 않는 주소) — 원인은 드라이버의 연결 거부, 그 원인은 소켓의 연결 거부 */
        fun unreachable(): SQLException = java.sql.SQLTransientConnectionException(
            "study-db - Connection is not available, request timed out after 2000ms (total=0, active=0, idle=0, waiting=0)", "08001",
            SQLException("Connection to 127.0.0.1:1 refused. Check that the hostname and port are correct", "08001", java.net.ConnectException("Connection refused")))

        /** HikariCP 의 풀이 바닥나 기다리다 시간이 넘은 꼴(실측: 연결 4개가 모두 쓰이는 중) — SQL 상태 · 원인이 없다 */
        fun poolExhausted(): SQLException = java.sql.SQLTransientConnectionException(
            "study-db - Connection is not available, request timed out after 2000ms (total=4, active=4, idle=0, waiting=1)")
    }

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
        val dataSource = CountingFailingDataSource()
        val elapsed = ElapsedTime()
        val store = ProgressStore(JdbcTemplate(dataSource), unavailablePause = Duration.ofSeconds(5), nanoTime = elapsed::read)

        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        val afterFirstFailure = dataSource.connectionAttempts
        assertTrue(afterFirstFailure >= 1, "처음 호출이 DB 에 닿아 보지 않았다")

        elapsed.advance(Duration.ofMillis(4_999))
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertThrows(DataAccessException::class.java) { store.recordAttempt(1, submission(), Instant.parse("2026-11-25T01:00:00Z")) }
        assertThrows(DataAccessException::class.java) { store.attemptsOf(1) }
        assertEquals(afterFirstFailure, dataSource.connectionAttempts, "쉼 동안에 DB 에 다시 닿아 봤다")

        elapsed.advance(Duration.ofMillis(2))                            // 처음 실패부터 5.001초 — 쉼이 지남
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertTrue(dataSource.connectionAttempts > afterFirstFailure, "쉼이 지났는데 다시 닿아 보지 않았다")
    }

    // ── 쉼은 DB 에 닿지 못했을 때만 걸고 흐른 시간으로 잰다 ─────────────────

    @Test
    fun `쉼은 흐른 시간으로 재므로 벽시계와 무관하게 쉼 길이가 지나면 다시 닿아 본다`() {
        // 저장소는 벽시계를 받지 않는다 — 실제 시각이 얼마든(뒤로 가도) 흐른 시간만 본다. 흐른 시간을 주지 않으면 쉼이 풀리지 않아야 한다
        val dataSource = CountingFailingDataSource()
        val elapsed = ElapsedTime()
        val store = ProgressStore(JdbcTemplate(dataSource), unavailablePause = Duration.ofSeconds(5), nanoTime = elapsed::read)
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        val afterFailure = dataSource.connectionAttempts

        Thread.sleep(20)                                                 // 벽시계는 흐르지만 흐른 시간(주입)은 그대로
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertEquals(afterFailure, dataSource.connectionAttempts, "주입한 흐른 시간이 아니라 다른 시계로 쉼을 쟀다")

        elapsed.advance(Duration.ofSeconds(5).plusMillis(1))
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertTrue(dataSource.connectionAttempts > afterFailure, "흐른 시간이 쉼 길이를 넘었는데 다시 닿아 보지 않았다")
    }

    @Test
    fun `쉼이 지난 뒤 다시 닿아 봐도 닿지 못하면 또 쉰다`() {
        val dataSource = CountingFailingDataSource()
        val elapsed = ElapsedTime()
        val store = ProgressStore(JdbcTemplate(dataSource), unavailablePause = Duration.ofSeconds(5), nanoTime = elapsed::read)

        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }   // 닿지 못함 → 첫 쉼
        val afterFirstFailure = dataSource.connectionAttempts

        elapsed.advance(Duration.ofSeconds(5).plusMillis(1))            // 첫 쉼이 지남 → 다시 닿아 본다(또 실패)
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        val afterSecondFailure = dataSource.connectionAttempts
        assertTrue(afterSecondFailure > afterFirstFailure, "첫 쉼이 지났는데 다시 닿아 보지 않았다")

        elapsed.advance(Duration.ofMillis(1))                            // 둘째 실패 바로 뒤 — 다시 쉬어야 한다
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertThrows(DataAccessException::class.java) { store.attemptsOf(1) }
        assertEquals(afterSecondFailure, dataSource.connectionAttempts, "쉼이 지난 뒤 또 닿지 못했는데 다시 쉬지 않았다")

        elapsed.advance(Duration.ofSeconds(5))                           // 둘째 실패부터 5.001초 — 둘째 쉼이 지남
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertTrue(dataSource.connectionAttempts > afterSecondFailure, "둘째 쉼이 지났는데 다시 닿아 보지 않았다")
    }

    @Test
    fun `풀이 바닥나 기다리다 넘은 것은 DB 장애가 아니라 쉼을 걸지 않는다`() {
        val dataSource = CountingFailingDataSource(::poolExhausted)
        val store = ProgressStore(JdbcTemplate(dataSource), unavailablePause = Duration.ofSeconds(5), nanoTime = ElapsedTime()::read)
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        val afterExhausted = dataSource.connectionAttempts
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertTrue(dataSource.connectionAttempts > afterExhausted, "풀이 바닥난 뒤의 다음 호출이 DB 에 닿아 보지 않았다(쉼이 걸렸다)")
    }

    @Test
    fun `SQL 오류 직후의 다음 호출은 쉼 없이 DB 에 다시 닿는다`() {
        var connectionAttempts = 0
        // 연결은 주지만 문장마다 키 충돌(23505)로 실패하는 DB
        val failingConnection = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "prepareStatement", "createStatement", "prepareCall" -> throw SQLException("duplicate key value violates unique constraint", "23505")
                "getAutoCommit" -> true
                "isClosed", "isReadOnly" -> false
                "isWrapperFor" -> false
                "getWarnings", "unwrap" -> null
                "toString" -> "failing-connection"
                "hashCode" -> 1
                "equals" -> false
                else -> if (method.returnType == Void.TYPE) Unit else null
            }
        } as Connection
        val dataSource = object : AbstractDataSource() {
            override fun getConnection(): Connection { connectionAttempts++; return failingConnection }
            override fun getConnection(username: String?, password: String?): Connection = getConnection()
        }
        val jdbc = JdbcTemplate(dataSource).apply { exceptionTranslator = org.springframework.jdbc.support.SQLStateSQLExceptionTranslator() }
        val store = ProgressStore(jdbc, unavailablePause = Duration.ofSeconds(5), nanoTime = ElapsedTime()::read)
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        val afterSqlError = connectionAttempts
        assertTrue(afterSqlError >= 1)
        assertThrows(DataAccessException::class.java) { store.findVisitor(hash) }
        assertTrue(connectionAttempts > afterSqlError, "SQL 오류 직후의 호출이 DB 에 닿아 보지 않았다(쉼이 걸렸다)")
    }
}
