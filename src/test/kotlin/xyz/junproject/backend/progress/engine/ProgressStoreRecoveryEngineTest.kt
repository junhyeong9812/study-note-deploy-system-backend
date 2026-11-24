package xyz.junproject.backend.progress.engine

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import xyz.junproject.backend.progress.ProgressConfiguration
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.infra.ProgressStore
import xyz.junproject.backend.progress.infra.ProgressStoreUnavailableTest
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 실제 PostgreSQL — 명세 15 · 16 의 쉼:
 * DB 에 닿지 못한 직후(쉼)에는 DB 가 살아나도 닿아 보지 않고, 흐른 시간이 쉼을 넘으면 다시 닿아 성공한다(15 · 16 흐른 시간).
 * 풀이 바닥나 기다리다 넘은 것은 쉼을 걸지 않는다(16). 넣기와 그 결과 읽기는 한 번의 DB 접근 — 넣은 뒤 DB 를 잃어도 저장됐다고 답한다(16).
 * DB 의 죽음은 연결을 주지 않는 겉감으로 흉내 낸다(실제 풀은 그 안에 그대로, 실패의 꼴은 HikariCP 의 실측 꼴).
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressStoreRecoveryEngineTest {
    private val schema = "progress_recovery_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val pool = EngineSupport.schemaPool(schema, size = 2)
    private val now = Instant.parse("2026-11-25T01:00:00Z")
    private val today = LocalDate.of(2026, 11, 25)

    /** [down] 이거나 [connectionsLeft] 가 0 이면 연결을 주지 않는다(닿지 못한 꼴). 연결을 준 · 거절한 횟수를 센다 */
    private class SwitchableDataSource(private val delegate: DataSource) : AbstractDataSource() {
        @Volatile var down = false
        @Volatile var connectionsLeft: Int? = null
        @Volatile var connectionAttempts = 0
        override fun getConnection(): Connection {
            connectionAttempts++
            connectionsLeft?.let { left -> if (left <= 0) throw ProgressStoreUnavailableTest.unreachable(); connectionsLeft = left - 1 }
            if (down) throw ProgressStoreUnavailableTest.unreachable()
            return delegate.connection
        }
        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }

    private class ElapsedTime(var nanos: Long = 1_000_000_000L) { fun read(): Long = nanos; fun advance(duration: Duration) { nanos += duration.toNanos() } }

    private fun submission(answer: String = "답", attemptId: UUID = UUID.randomUUID()) =
        AnswerSubmission(attemptId, "cs/os/process", 1, answer, "a".repeat(40), "b".repeat(64))

    @AfterAll
    fun close() {
        administrationJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        pool.close()
        administration.close()
    }

    @Test
    fun `DB 가 살아나도 쉼 동안에는 닿아 보지 않고 흐른 시간이 쉼을 넘으면 다시 닿아 성공한다`() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        val dataSource = SwitchableDataSource(pool)
        val elapsed = ElapsedTime()
        val store = ProgressStore(JdbcTemplate(dataSource), unavailablePause = Duration.ofSeconds(5), nanoTime = elapsed::read)
        store.createSchema()
        val created = store.createVisitor("r".repeat(64), today, now)

        dataSource.down = true
        assertThrows(DataAccessException::class.java) { store.findVisitor("r".repeat(64)) }
        val attemptsAfterFailure = dataSource.connectionAttempts

        dataSource.down = false                                          // DB 가 살아남
        elapsed.advance(Duration.ofSeconds(4))
        assertThrows(DataAccessException::class.java) { store.findVisitor("r".repeat(64)) }
        assertEquals(attemptsAfterFailure, dataSource.connectionAttempts, "쉼 동안에 DB 에 닿아 봤다")

        elapsed.advance(Duration.ofSeconds(2))                           // 실패부터 6초 — 쉼이 지남
        assertEquals(created, store.findVisitor("r".repeat(64)))
        assertTrue(dataSource.connectionAttempts > attemptsAfterFailure)
        assertNotNull(store.createVisitor("s".repeat(64), today, now))
    }

    // ── 리뷰 루프 2 반영(명세 16) ─────────────────────────

    @Test
    fun `기존 방문자의 시도와 채점은 연결 한 번으로 넣고 읽어 넣은 뒤 DB 를 잃어도 저장됐다고 답한다`() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        val dataSource = SwitchableDataSource(pool)
        val elapsed = ElapsedTime()
        val store = ProgressStore(JdbcTemplate(dataSource), unavailablePause = Duration.ofSeconds(5), nanoTime = elapsed::read)
        store.createSchema()
        val owner = store.createVisitor("o".repeat(64), today, now)
        val submitted = submission(answer = "저장된 답")

        // 연결을 하나만 준다 — 그 뒤의 연결 요청은 "닿지 못함"(쉼이 걸린다)
        dataSource.connectionsLeft = 1
        var before = dataSource.connectionAttempts
        val stored = store.recordAttempt(owner.id, submitted, now)
        assertEquals("저장된 답", stored?.answer, "넣은 뒤의 읽기가 실패했다")
        assertEquals(1, dataSource.connectionAttempts - before, "시도 넣기가 연결을 한 번보다 많이 얻었다")

        elapsed.advance(Duration.ofSeconds(10)); dataSource.connectionsLeft = 1; before = dataSource.connectionAttempts
        assertEquals(stored, store.recordAttempt(owner.id, submitted.copy(answer = "둘째"), now.plusSeconds(1)), "같은 번호의 재전송")
        assertEquals(1, dataSource.connectionAttempts - before)

        elapsed.advance(Duration.ofSeconds(10)); dataSource.connectionsLeft = 1; before = dataSource.connectionAttempts
        val graded = store.recordGrade(owner.id, submitted.attemptId, Grade.CORRECT, now.plusSeconds(30))
        assertEquals(stored!!.copy(grade = Grade.CORRECT, gradedAt = now.plusSeconds(30)), graded, "채점 뒤의 읽기가 실패했다")
        assertEquals(1, dataSource.connectionAttempts - before, "채점이 연결을 한 번보다 많이 얻었다")

        elapsed.advance(Duration.ofSeconds(10)); dataSource.connectionsLeft = null
        assertEquals(graded, store.findAttempt(owner.id, submitted.attemptId))
    }

    @Test
    fun `운영 풀이 바닥나 기다리다 넘은 뒤에도 쉼이 걸리지 않아 풀이 풀리면 곧바로 성공한다`() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        val configuration = ProgressConfiguration()
        val jdbc = configuration.studyJdbc("${EngineSupport.databaseUrl}?currentSchema=$schema", EngineSupport.databaseUser, EngineSupport.databasePassword)
        val holder = EngineSupport.schemaPool(schema, size = 1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val store = configuration.progressStore(jdbc)
            store.createSchema()
            val takenId = UUID.randomUUID()
            // 다른 세션이 시도 번호 X 를 넣고 커밋하지 않은 채 둔다
            holder.connection.use { holding ->
                holding.autoCommit = false
                val holderVisitor = holding.prepareStatement(
                    "INSERT INTO study_visitor (token_hash, last_visit_on, created_at) VALUES (?, ?, now()) RETURNING id").use { statement ->
                    statement.setString(1, "z".repeat(64)); statement.setObject(2, today)
                    statement.executeQuery().use { it.next(); it.getLong(1) }
                }
                holding.prepareStatement(
                    "INSERT INTO quiz_attempt (attempt_id, visitor_id, topic_path, question_number, answer, content_sha, question_hash, submitted_at) " +
                        "VALUES (?, ?, 'cs/os/process', 1, '붙잡는 답', 'a', 'b', now())").use { statement ->
                    statement.setObject(1, takenId); statement.setLong(2, holderVisitor); statement.executeUpdate()
                }
                // 쿠키 없는 첫 답 넷이 같은 번호로 기다리며 풀의 연결 4개를 붙잡는다
                val waiting = (0 until 4).map { index ->
                    executor.submit(Callable { store.recordFirstAttempt("w$index".padEnd(64, 'w'), today, submission(attemptId = takenId), now) })
                }
                val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
                while (lockWaiters() < 4 && System.nanoTime() < deadline) Thread.sleep(50)
                assertEquals(4, lockWaiters(), "첫 답 넷이 줄의 잠금을 기다리지 않는다 — 재현 조건이 서지 않았다")

                // 다섯째 호출은 풀이 바닥나 실패한다(DB 는 멀쩡하다)
                assertThrows(DataAccessException::class.java) { store.findVisitor("q".repeat(64)) }

                holding.rollback()                                       // 붙잡던 세션을 푼다
                waiting.forEach { it.get(30, TimeUnit.SECONDS) }
            }
            // 풀이 풀린 직후 — 쉼이 걸리지 않았으면 곧바로 성공한다
            assertNull(store.findVisitor("q".repeat(64)))
            assertNotNull(store.recordFirstAttempt("n".repeat(64), today, submission(), now))
        } finally {
            executor.shutdownNow()
            holder.close()
            configuration.closePool()
        }
    }

    /** 이 스키마의 줄 잠금을 기다리는 연결 수 */
    private fun lockWaiters() = administrationJdbc.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Int::class.java)!!
}
