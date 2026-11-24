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
import xyz.junproject.backend.progress.usecase.AttemptsOutcome
import xyz.junproject.backend.progress.usecase.PurgeOutcome
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    /** 다른 자리(blue · green) — 같은 스키마를 다른 풀로 */
    private val greenPool = EngineSupport.schemaPool(schema)
    private val greenStore = ProgressStore(JdbcTemplate(greenPool))
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
        greenPool.close()
        administration.close()
    }

    private fun service(logs: LogCapture, slot: ProgressStore = store, purgeBatchSize: Int = StudyProgressService.PURGE_BATCH_SIZE) =
        StudyProgressService(slot, Clock.fixed(now, ZoneOffset.UTC), logs.requestLog, purgeBatchSize = purgeBatchSize)

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

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
            val token = outcome.cookieToken
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
            val intruderToken = intruder.cookieToken
            assertNotEquals(forged, intruderToken)

            val intruderVisitor = (service.identify("req-3", intruderToken) as VisitorLookup.Found).visitor
            assertEquals(listOf("침입자의 답"), store.attemptsOf(intruderVisitor.id).map { it.answer })
            assertEquals(GradeOutcome.AttemptNotFound, service.grade("req-4", intruderToken, owner.attempt.attemptId, Grade.WRONG))
            assertEquals(DeleteOutcome.Deleted(1), service.deleteMine("req-5", intruderToken))

            val ownerVisitor = (service.identify("req-6", owner.cookieToken) as VisitorLookup.Found).visitor
            assertEquals(listOf("주인의 답" to null), store.attemptsOf(ownerVisitor.id).map { it.answer to it.grade })
        }
    }

    @Test
    fun `실제 DB 오류가 나도 로그에 토큰과 쓴 답의 본문이 나오지 않는다`() {
        val answer = "로그에-나오면-안-되는-답-본문"
        LogCapture().use { logs ->
            val service = service(logs)
            val saved = service.submitAnswer("req-1", null, submission(answer)) as SubmitOutcome.Saved
            val token = saved.cookieToken
            val other = service.submitAnswer("req-2", null, submission(answer)) as SubmitOutcome.Saved
            // 같은 시도 번호를 남이 씀 — 실제 키 충돌 경로
            assertEquals(SubmitOutcome.AttemptIdTaken, service.submitAnswer("req-3", other.cookieToken, submission(answer, saved.attempt.attemptId)))
            service.grade("req-4", token, saved.attempt.attemptId, Grade.PARTIAL)
            // 표를 없애 실제 DB 오류를 낸다
            jdbc.execute("DROP TABLE quiz_grade, quiz_attempt CASCADE")
            assertEquals(SubmitOutcome.StorageUnavailable, service.submitAnswer("req-5", token, submission(answer)))

            val lines = logs.lines
            assertTrue(lines.any { it.contains("cs/os/process") }, "기록 로그가 없다: $lines")
            listOf(answer, token, other.cookieToken).forEach { secret ->
                assertTrue(lines.none { it.contains(secret) }, "로그에 나오면 안 되는 값: $secret\n$lines")
            }
        }
    }

    // ── 리뷰 루프 1 반영(명세 15) ─────────────────────────

    @Test
    fun `쿠키 없는 첫 답의 시도 번호가 남의 것이면 방문자 줄이 늘지 않는다`() {
        LogCapture().use { logs ->
            val service = service(logs)
            val owner = service.submitAnswer("req-1", null, submission("주인의 답")) as SubmitOutcome.Saved
            assertEquals(1, count("study_visitor"))
            assertEquals(SubmitOutcome.AttemptIdTaken, service.submitAnswer("req-2", null, submission("남의 답", owner.attempt.attemptId)))
            assertEquals(1, count("study_visitor"))
            assertEquals(1, count("quiz_attempt"))
        }
    }

    @Test
    fun `쿠키 없는 첫 답에서 시도 넣기가 DB 오류로 실패하면 방문자 줄도 남지 않는다`() {
        jdbc.execute("CREATE FUNCTION reject_attempt() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'rejected by test'; END \$\$")
        jdbc.execute("CREATE TRIGGER reject_attempt BEFORE INSERT ON quiz_attempt FOR EACH ROW EXECUTE FUNCTION reject_attempt()")
        LogCapture().use { logs ->
            assertEquals(SubmitOutcome.StorageUnavailable, service(logs).submitAnswer("req-1", null, submission("답")))
        }
        assertEquals(0, count("study_visitor"))
        assertEquals(0, count("quiz_attempt"))
    }

    @Test
    fun `NUL 이 든 답은 잘못된 입력이고 방문자 줄도 시도 줄도 남지 않는다`() {
        LogCapture().use { logs ->
            assertEquals(SubmitOutcome.InvalidInput, service(logs).submitAnswer("req-1", null, submission("앞\u0000뒤")))
            assertTrue(logs.lines.none { it.startsWith("ERROR") }, logs.lines.toString())
        }
        assertEquals(0, count("study_visitor"))
        assertEquals(0, count("quiz_attempt"))
    }

    @Test
    fun `쿠키 없는 같은 시도 번호를 두 자리가 동시에 내면 방문자 줄 하나 시도 줄 하나만 남는다`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            LogCapture().use { logs ->
                val slots = listOf(service(logs, store), service(logs, greenStore))
                repeat(5) { round ->
                    val visitorsBefore = count("study_visitor")
                    val attemptsBefore = count("quiz_attempt")
                    val attemptId = UUID.randomUUID()
                    val start = CountDownLatch(1)
                    val futures = slots.mapIndexed { index, slot ->
                        executor.submit(Callable { start.await(); slot.submitAnswer("req-$round-$index", null, submission("답$index", attemptId)) })
                    }
                    start.countDown()
                    val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
                    val saved = outcomes.filterIsInstance<SubmitOutcome.Saved>()
                    assertEquals(1, saved.size, "${round}번째: $outcomes")
                    val loser = outcomes.single { it !is SubmitOutcome.Saved }
                    assertTrue(loser == SubmitOutcome.AttemptIdTaken || loser == SubmitOutcome.StorageUnavailable, "${round}번째 진 쪽: $loser")
                    assertEquals(visitorsBefore + 1, count("study_visitor"), "${round}번째: $outcomes")
                    assertEquals(attemptsBefore + 1, count("quiz_attempt"), "${round}번째: $outcomes")
                    val owner = (slots[0].identify("req-check", saved.single().cookieToken) as VisitorLookup.Found).visitor
                    assertEquals(listOf(attemptId), store.attemptsOf(owner.id).map { it.attemptId })
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `보관 삭제는 묶음 크기보다 많은 방문자도 전부 지우고 기준 당일은 남긴다`() {
        val cutoffDay = java.time.LocalDate.of(2025, 11, 25)                // 한국 2026-11-25 의 1년 전
        val expired = (0 until 8).map { store.createVisitor("x$it".padEnd(64, 'x'), cutoffDay.minusDays(it + 1L), now) }
        expired.forEach { store.recordAttempt(it.id, submission("답"), now) }
        val onCutoff = store.createVisitor("c".repeat(64), cutoffDay, now)
        store.recordAttempt(onCutoff.id, submission("답"), now)
        LogCapture().use { logs ->
            assertEquals(PurgeOutcome.Purged(8, 0), service(logs, purgeBatchSize = 3).purgeInactiveVisitors("req-1"))
        }
        assertEquals(listOf(onCutoff.id), jdbc.queryForList("SELECT id FROM study_visitor", Long::class.java))
        assertEquals(1, count("quiz_attempt"))
    }

    @Test
    fun `시도 목록은 그 방문자의 것만 나온다`() {
        LogCapture().use { logs ->
            val service = service(logs)
            val mine = service.submitAnswer("req-1", null, submission("내 답")) as SubmitOutcome.Saved
            service.submitAnswer("req-2", null, submission("남의 답"))
            assertEquals(AttemptsOutcome.Listed(listOf(mine.attempt)), service.attempts("req-3", mine.cookieToken))
            assertEquals(AttemptsOutcome.Listed(emptyList()), service.attempts("req-4", null))
            assertEquals(2, count("study_visitor"))
        }
    }
}
