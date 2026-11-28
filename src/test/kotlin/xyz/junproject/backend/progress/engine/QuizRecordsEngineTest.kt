package xyz.junproject.backend.progress.engine

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.infra.ProgressStore
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.summaryOf
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 실제 PostgreSQL — 명세 18.2 · 16 · 17 의 읽기 계약(task 03b 에서 더한 함수):
 * 주제 단위로 읽는다(그 방문자의 그 주제만, 본문 없이, 주제 조건의 색인이 있다) · 쓴 답의 본문은 질문 하나 단위로만 ·
 * 복습 목록은 가장 최근 줄부터 정한 수까지.
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuizRecordsEngineTest {
    private val schema = "quiz_records_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val pool = EngineSupport.schemaPool(schema)
    private val jdbc = JdbcTemplate(pool)
    private val store = ProgressStore(jdbc)
    private val now = Instant.parse("2026-11-25T01:00:00Z")
    private val today = LocalDate.of(2026, 11, 25)

    @BeforeEach
    fun reset() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        store.createSchema()
    }

    @AfterAll
    fun close() {
        administrationJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        pool.close()
        administration.close()
    }

    private fun record(visitorId: Long, topic: String, number: Int, submittedAt: Instant, answer: String = "답 $topic $number") =
        store.recordAttempt(visitorId, AnswerSubmission(UUID.randomUUID(), topic, number, answer, "a".repeat(40), "b".repeat(64)), submittedAt)!!

    @Test
    fun `주제 단위 읽기는 그 방문자의 그 주제 시도만 낸 차례로 채점과 함께 낸다`() {
        val visitor = store.createVisitor("h1", today, now)
        val other = store.createVisitor("h2", today, now)
        val later = record(visitor.id, "cs/os/process", 2, now)
        val earlier = record(visitor.id, "cs/os/process", 1, now.minusSeconds(60))
        record(visitor.id, "cs/net/tcp", 1, now.minusSeconds(30))
        record(visitor.id, "cs/os/process-extra", 1, now.minusSeconds(30))   // 앞부분이 같은 다른 주제
        record(other.id, "cs/os/process", 1, now.minusSeconds(10))
        store.recordGrade(visitor.id, earlier.attemptId, Grade.PARTIAL, now)

        val attempts = store.attemptsOfTopic(visitor.id, "cs/os/process")

        assertEquals(listOf(earlier.attemptId, later.attemptId), attempts.map { it.attemptId })
        assertEquals(summaryOf(store.findAttempt(visitor.id, earlier.attemptId)!!), attempts.first())
        assertEquals(Grade.PARTIAL, attempts.first().grade)
        assertEquals(emptyList<Any>(), store.attemptsOfTopic(visitor.id, "cs/os"))
    }

    @Test
    fun `quiz_attempt 에 방문자와 주제로 찾는 색인이 있다`() {
        val definitions = jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = 'quiz_attempt'", String::class.java, schema)
        assertTrue(definitions.filterNotNull().any { it.contains("visitor_id") && it.contains("topic_path") }, definitions.toString())
    }

    @Test
    fun `질문 하나 단위 읽기는 그 방문자의 그 질문 시도만 답 본문과 함께 낸 차례로 낸다`() {
        val visitor = store.createVisitor("h1", today, now)
        val other = store.createVisitor("h2", today, now)
        val second = record(visitor.id, "cs/os/process", 2, now, answer = "두 번째 답")
        val first = record(visitor.id, "cs/os/process", 2, now.minusSeconds(60), answer = "첫 번째 답")
        record(visitor.id, "cs/os/process", 1, now, answer = "다른 질문의 답")
        record(visitor.id, "cs/net/tcp", 2, now, answer = "다른 주제의 답")
        record(other.id, "cs/os/process", 2, now, answer = "남의 답")
        store.recordGrade(visitor.id, first.attemptId, Grade.WRONG, now)

        val attempts = store.attemptsOfQuestion(visitor.id, "cs/os/process", 2)

        assertEquals(listOf("첫 번째 답", "두 번째 답"), attempts.map { it.answer })
        assertEquals(listOf(first.attemptId, second.attemptId), attempts.map { it.attemptId })
        assertEquals(Grade.WRONG, attempts.first().grade)
    }

    @Test
    fun `최근 시도 읽기는 가장 최근 줄부터 정한 수까지 낸 차례로 낸다`() {
        val visitor = store.createVisitor("h1", today, now)
        val other = store.createVisitor("h2", today, now)
        val stored = (5 downTo 1).map { minutesAgo -> record(visitor.id, "cs/os/process", minutesAgo, now.minusSeconds(60L * minutesAgo)) }
        record(other.id, "cs/os/process", 1, now)

        val recent = store.recentAttemptsOf(visitor.id, 3)

        assertEquals(stored.takeLast(3).map { it.attemptId }, recent.map { it.attemptId })
        assertEquals(5, store.recentAttemptsOf(visitor.id, 10).size)
    }
}
