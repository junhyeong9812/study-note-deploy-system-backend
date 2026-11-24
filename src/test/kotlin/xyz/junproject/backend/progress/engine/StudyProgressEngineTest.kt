package xyz.junproject.backend.progress.engine

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.progress.infra.ProgressStore
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.DeleteOutcome
import xyz.junproject.backend.progress.usecase.GradeOutcome
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.progress.usecase.SubmitOutcome
import xyz.junproject.backend.progress.usecase.VisitorLookup
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 실제 PostgreSQL 위의 서비스 — 명세 2.1 · 2.2 · 11.2: 토큰 원문은 DB 어디에도 없고 해시만 있다,
 * 쓴 답의 본문 · 토큰은 로그에 없다(실제 DB 오류 경로 포함), 지어낸 토큰으로 남의 기록에 닿지 않는다.
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudyProgressEngineTest {
    private val schema = "progress_service_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val pool = EngineSupport.schemaPool(schema)
    private val jdbc = JdbcTemplate(pool)
    private val store = ProgressStore(jdbc)
    private val now = Instant.parse("2026-11-25T01:00:00Z")

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

    private fun service(logs: LogCapture) = StudyProgressService(store, Clock.fixed(now, ZoneOffset.UTC), logs.requestLog)

    private fun submission(answer: String, attemptId: UUID = UUID.randomUUID()) =
        AnswerSubmission(attemptId, "cs/os/process", 2, answer, "a".repeat(40), "b".repeat(64))

    /** 세 표의 모든 줄을 글자로 */
    private fun everyRowAsText(): List<String> = listOf("study_visitor", "quiz_attempt", "quiz_grade").flatMap { table ->
        jdbc.queryForList("SELECT row_to_json(t)::text FROM $table t", String::class.java).filterNotNull()
    }

    @Test
    fun `발급한 토큰의 원문은 DB 어디에도 없고 방문자 줄에는 그 해시가 있다`() {
        LogCapture().use { logs ->
            val outcome = service(logs).submitAnswer("req-1", null, submission("첫 답")) as SubmitOutcome.Saved
            val token = requireNotNull(outcome.issuedToken)
            assertTrue(VisitorToken.isWellFormed(token))
            service(logs).grade("req-2", token, outcome.attempt.attemptId, Grade.CORRECT)

            val rows = everyRowAsText()
            assertTrue(rows.any { it.contains("첫 답") }, "답이 저장되지 않았다: $rows")
            assertTrue(rows.none { it.contains(token) }, "토큰 원문이 DB 에 있다: $rows")
            assertEquals(listOf(VisitorToken.hash(token)), jdbc.queryForList("SELECT token_hash FROM study_visitor", String::class.java))
        }
    }

    @Test
    fun `지어낸 토큰은 새 방문자가 되고 남의 기록을 읽거나 지우지 못한다`() {
        LogCapture().use { logs ->
            val service = service(logs)
            val owner = service.submitAnswer("req-1", null, submission("주인의 답")) as SubmitOutcome.Saved
            val forged = "A".repeat(43)
            val intruder = service.submitAnswer("req-2", forged, submission("침입자의 답")) as SubmitOutcome.Saved
            val intruderToken = requireNotNull(intruder.issuedToken)
            assertNotEquals(forged, intruderToken)

            val intruderVisitor = (service.identify("req-3", intruderToken) as VisitorLookup.Found).visitor
            assertEquals(listOf("침입자의 답"), store.attemptsOf(intruderVisitor.id).map { it.answer })
            assertEquals(GradeOutcome.AttemptNotFound, service.grade("req-4", intruderToken, owner.attempt.attemptId, Grade.WRONG))
            assertEquals(DeleteOutcome.Deleted(1), service.deleteMine("req-5", intruderToken))

            val ownerVisitor = (service.identify("req-6", owner.issuedToken) as VisitorLookup.Found).visitor
            assertEquals(listOf("주인의 답" to null), store.attemptsOf(ownerVisitor.id).map { it.answer to it.grade })
        }
    }

    @Test
    fun `실제 DB 오류가 나도 로그에 토큰과 쓴 답의 본문이 나오지 않는다`() {
        val answer = "로그에-나오면-안-되는-답-본문"
        LogCapture().use { logs ->
            val service = service(logs)
            val saved = service.submitAnswer("req-1", null, submission(answer)) as SubmitOutcome.Saved
            val token = requireNotNull(saved.issuedToken)
            val other = service.submitAnswer("req-2", null, submission(answer)) as SubmitOutcome.Saved
            // 같은 시도 번호를 남이 씀 — 실제 키 충돌 경로
            assertEquals(SubmitOutcome.AttemptIdTaken, service.submitAnswer("req-3", other.issuedToken, submission(answer, saved.attempt.attemptId)))
            service.grade("req-4", token, saved.attempt.attemptId, Grade.PARTIAL)
            // 표를 없애 실제 DB 오류를 낸다
            jdbc.execute("DROP TABLE quiz_grade, quiz_attempt CASCADE")
            assertEquals(SubmitOutcome.StorageUnavailable, service.submitAnswer("req-5", token, submission(answer)))

            val lines = logs.lines
            assertTrue(lines.any { it.contains("cs/os/process") }, "기록 로그가 없다: $lines")
            listOf(answer, token, requireNotNull(other.issuedToken)).forEach { secret ->
                assertTrue(lines.none { it.contains(secret) }, "로그에 나오면 안 되는 값: $secret\n$lines")
            }
        }
    }
}
