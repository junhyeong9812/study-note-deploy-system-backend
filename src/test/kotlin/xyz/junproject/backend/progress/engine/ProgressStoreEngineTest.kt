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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 PostgreSQL — 명세 2.2 · 11.2 의 저장소 계약: 표 만들기가 두 번 · 두 자리에서 돌아도 안전, 시도 · 채점은 덧붙이기만
 * (어느 줄도 고쳐지지 않음 — 줄의 xmin 으로 본다), 같은 시도 번호는 한 줄, 방문자별 조회 · 세기 · 지우기, 1년 보관 삭제의 경계.
 * 각 사례는 빈 전용 스키마(`progress_engine`)에서 돈다. 표 이름(study_visitor · quiz_attempt · quiz_grade)은 계약이다.
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressStoreEngineTest {
    private val schema = "progress_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val blue = EngineSupport.schemaPool(schema)
    private val green = EngineSupport.schemaPool(schema)
    private val jdbc = JdbcTemplate(blue)
    private val store = ProgressStore(jdbc)
    private val greenStore = ProgressStore(JdbcTemplate(green))
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
        blue.close()
        green.close()
        administration.close()
    }

    private fun submission(answer: String = "답", attemptId: UUID = UUID.randomUUID(), questionNumber: Int = 1) =
        AnswerSubmission(attemptId, "cs/os/process", questionNumber, answer, "a".repeat(40), "b".repeat(64))

    private fun visitor(hash: String, lastVisitOn: LocalDate = today) = store.createVisitor(hash, lastVisitOn, now)

    private fun xminOfAttempt(attemptId: UUID) = jdbc.queryForObject("SELECT xmin::text FROM quiz_attempt WHERE attempt_id = ?", String::class.java, attemptId)
    private fun xminOfGrade(attemptId: UUID) = jdbc.queryForObject("SELECT xmin::text FROM quiz_grade WHERE attempt_id = ?", String::class.java, attemptId)
    private fun count(sql: String, vararg arguments: Any) = jdbc.queryForObject(sql, Int::class.java, *arguments)!!

    /** 방문자 하나에 딸린 줄의 수 — 방문자 줄 · 시도 · 채점 */
    private fun rowsOf(visitorId: Long) = Triple(
        count("SELECT count(*) FROM study_visitor WHERE id = ?", visitorId),
        count("SELECT count(*) FROM quiz_attempt WHERE visitor_id = ?", visitorId),
        count("SELECT count(*) FROM quiz_grade g JOIN quiz_attempt a ON a.attempt_id = g.attempt_id WHERE a.visitor_id = ?", visitorId),
    )

    // ── 표 만들기 ─────────────────────────────────────────

    @Test
    fun `표 만들기를 다시 돌려도 있던 줄이 그대로 남는다`() {
        val owner = visitor("h".repeat(64))
        store.recordAttempt(owner.id, submission(), now)
        store.createSchema()
        greenStore.createSchema()
        assertEquals(1, store.attemptsOf(owner.id).size)
        assertEquals(owner, store.findVisitor("h".repeat(64)))
    }

    @Test
    fun `두 자리가 동시에 표를 만들어도 둘 다 실패하지 않는다`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                EngineSupport.recreateSchema(administrationJdbc, schema)
                val start = CountDownLatch(1)
                val futures = listOf(store, greenStore).map { slot -> executor.submit(Callable { start.await(); slot.createSchema() }) }
                start.countDown()
                futures.forEach { future ->
                    try { future.get(30, TimeUnit.SECONDS) } catch (error: Exception) { fail<Unit>("${round}번째 바퀴에서 표 만들기가 실패했다: ${error.cause ?: error}") }
                }
                assertNotNull(store.createVisitor("r$round".padEnd(64, 'x'), today, now))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // ── 방문자 ────────────────────────────────────────────

    @Test
    fun `방문자 줄에는 해시와 마지막 방문 날짜가 있고 계정 칸은 비어 있다`() {
        val hash = "c".repeat(64)
        val created = visitor(hash)
        assertEquals(today, created.lastVisitOn)
        val row = jdbc.queryForMap("SELECT token_hash, account_id, last_visit_on FROM study_visitor WHERE id = ?", created.id)
        assertEquals(hash, row["token_hash"])
        assertNull(row["account_id"])
        assertEquals(java.sql.Date.valueOf(today), row["last_visit_on"])
    }

    @Test
    fun `목록에 없는 해시는 없음이고 있는 해시는 그 방문자다`() {
        val created = visitor("d".repeat(64))
        assertNull(store.findVisitor("e".repeat(64)))
        assertEquals(created, store.findVisitor("d".repeat(64)))
    }

    @Test
    fun `마지막 방문을 갱신하면 다음 조회에 그 날짜가 나온다`() {
        val created = visitor("d".repeat(64), lastVisitOn = today.minusDays(3))
        store.touchVisit(created.id, today)
        assertEquals(today, store.findVisitor("d".repeat(64))?.lastVisitOn)
    }

    // ── 시도 · 채점 ───────────────────────────────────────

    @Test
    fun `시도를 덧붙이면 낸 내용 그대로 저장된다`() {
        val owner = visitor("h".repeat(64))
        val submitted = submission(answer = "프로세스는 실행 중인 프로그램")
        val stored = store.recordAttempt(owner.id, submitted, now)
        val expected = xyz.junproject.backend.progress.usecase.StoredAttempt(submitted.attemptId, "cs/os/process", 1, "프로세스는 실행 중인 프로그램",
            "a".repeat(40), "b".repeat(64), now, null, null)
        assertEquals(expected, stored)
        assertEquals(listOf(summaryOf(expected)), store.attemptsOf(owner.id))
        assertEquals(expected, store.findAttempt(owner.id, submitted.attemptId))
    }

    @Test
    fun `같은 시도 번호의 재제출은 한 줄만 남고 처음 줄을 고치지 않은 채 돌려준다`() {
        val owner = visitor("h".repeat(64))
        val attemptId = UUID.randomUUID()
        val first = store.recordAttempt(owner.id, submission(answer = "첫 답", attemptId = attemptId), now)
        val xmin = xminOfAttempt(attemptId)
        val second = store.recordAttempt(owner.id, submission(answer = "둘째 답", attemptId = attemptId), now.plusSeconds(5))
        assertEquals(first, second)
        assertEquals("첫 답", second?.answer)
        assertEquals(1, count("SELECT count(*) FROM quiz_attempt WHERE attempt_id = ?", attemptId))
        assertEquals(xmin, xminOfAttempt(attemptId), "처음 줄이 고쳐졌다")
    }

    @Test
    fun `두 자리가 같은 시도 번호를 동시에 저장해도 한 줄만 남고 모두 같은 결과를 받는다`() {
        val owner = visitor("h".repeat(64))
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(5) {
                val attemptId = UUID.randomUUID()
                val start = CountDownLatch(1)
                val futures = (0 until 8).map { index ->
                    val slot = if (index % 2 == 0) store else greenStore
                    executor.submit(Callable { start.await(); slot.recordAttempt(owner.id, submission(answer = "답$index", attemptId = attemptId), now.plusMillis(index.toLong())) })
                }
                start.countDown()
                val results = futures.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, results.toSet().size, results.toString())
                assertNotNull(results.first())
                assertEquals(1, count("SELECT count(*) FROM quiz_attempt WHERE attempt_id = ?", attemptId))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `다른 방문자가 쓴 시도 번호는 없음으로 돌아오고 주인의 줄은 그대로다`() {
        val owner = visitor("a".repeat(64))
        val other = visitor("b".repeat(64))
        val attemptId = UUID.randomUUID()
        store.recordAttempt(owner.id, submission(answer = "주인의 답", attemptId = attemptId), now)
        val xmin = xminOfAttempt(attemptId)
        assertNull(store.recordAttempt(other.id, submission(answer = "남의 답", attemptId = attemptId), now))
        assertEquals(listOf(attemptId), store.attemptsOf(owner.id).map { it.attemptId })
        assertEquals("주인의 답", store.findAttempt(owner.id, attemptId)?.answer)
        assertEquals(emptyList<Any>(), store.attemptsOf(other.id))
        assertEquals(xmin, xminOfAttempt(attemptId))
    }

    @Test
    fun `채점은 시도 줄을 고치지 않고 따로 덧붙는다`() {
        val owner = visitor("h".repeat(64))
        val submitted = submission()
        val stored = store.recordAttempt(owner.id, submitted, now)!!
        val xmin = xminOfAttempt(submitted.attemptId)
        val gradedAt = now.plusSeconds(30)
        val graded = store.recordGrade(owner.id, submitted.attemptId, Grade.CORRECT, gradedAt)
        assertEquals(stored.copy(grade = Grade.CORRECT, gradedAt = gradedAt), graded)
        assertEquals(xmin, xminOfAttempt(submitted.attemptId), "채점이 시도 줄을 고쳤다")
        assertEquals(1, count("SELECT count(*) FROM quiz_grade WHERE attempt_id = ?", submitted.attemptId))
        assertEquals(listOf(summaryOf(graded!!)), store.attemptsOf(owner.id))
    }

    @Test
    fun `같은 시도를 다시 채점해도 첫 채점 줄이 고쳐지지 않고 그대로 돌아온다`() {
        val owner = visitor("h".repeat(64))
        val submitted = submission()
        store.recordAttempt(owner.id, submitted, now)
        val first = store.recordGrade(owner.id, submitted.attemptId, Grade.WRONG, now.plusSeconds(10))
        val xmin = xminOfGrade(submitted.attemptId)
        val again = store.recordGrade(owner.id, submitted.attemptId, Grade.CORRECT, now.plusSeconds(20))
        assertEquals(first, again)
        assertEquals(Grade.WRONG, again?.grade)
        assertEquals(1, count("SELECT count(*) FROM quiz_grade WHERE attempt_id = ?", submitted.attemptId))
        assertEquals(xmin, xminOfGrade(submitted.attemptId))
    }

    @Test
    fun `남의 시도나 없는 시도는 채점할 수 없다`() {
        val owner = visitor("a".repeat(64))
        val other = visitor("b".repeat(64))
        val submitted = submission()
        store.recordAttempt(owner.id, submitted, now)
        assertNull(store.recordGrade(other.id, submitted.attemptId, Grade.CORRECT, now))
        assertNull(store.recordGrade(owner.id, UUID.randomUUID(), Grade.CORRECT, now))
        assertEquals(0, count("SELECT count(*) FROM quiz_grade"))
    }

    @Test
    fun `시도 목록은 그 방문자의 것만 낸 시각 차례로 채점과 함께 나온다`() {
        val owner = visitor("a".repeat(64))
        val other = visitor("b".repeat(64))
        val later = submission(questionNumber = 2)
        val earlier = submission(questionNumber = 1)
        store.recordAttempt(owner.id, later, now.plusSeconds(60))
        store.recordAttempt(owner.id, earlier, now)
        store.recordAttempt(other.id, submission(questionNumber = 9), now.minusSeconds(60))
        store.recordGrade(owner.id, later.attemptId, Grade.PARTIAL, now.plusSeconds(90))
        val attempts = store.attemptsOf(owner.id)
        assertEquals(listOf(earlier.attemptId, later.attemptId), attempts.map { it.attemptId })
        assertEquals(listOf(null, Grade.PARTIAL), attempts.map { it.grade })
        assertEquals(listOf(null, now.plusSeconds(90)), attempts.map { it.gradedAt })
    }

    @Test
    fun `구간 세기는 시작은 넣고 끝은 뺀다`() {
        val owner = visitor("a".repeat(64))
        val other = visitor("b".repeat(64))
        val from = Instant.parse("2026-11-24T15:00:00Z")
        val until = Instant.parse("2026-11-25T15:00:00Z")
        listOf(from.minusMillis(1), from, until.minusMillis(1), until).forEach { store.recordAttempt(owner.id, submission(), it) }
        store.recordAttempt(other.id, submission(), from)
        assertEquals(2, store.countAttemptsBetween(owner.id, from, until))
        assertEquals(4, store.countAttempts(owner.id))
    }

    // ── 지우기 · 보관 ─────────────────────────────────────

    @Test
    fun `방문자를 지우면 그 시도 채점 방문자 줄만 지우고 지운 시도의 수를 돌려준다`() {
        val mine = visitor("a".repeat(64))
        val other = visitor("b".repeat(64))
        repeat(3) { store.recordAttempt(mine.id, submission(), now).also { attempt -> store.recordGrade(mine.id, attempt!!.attemptId, Grade.CORRECT, now) } }
        repeat(2) { store.recordAttempt(other.id, submission(), now).also { attempt -> store.recordGrade(other.id, attempt!!.attemptId, Grade.WRONG, now) } }
        val otherBefore = rowsOf(other.id)
        assertEquals(Triple(1, 2, 2), otherBefore)

        assertEquals(3, store.countAttempts(mine.id))
        assertEquals(3, store.deleteVisitor(mine.id))

        assertEquals(Triple(0, 0, 0), rowsOf(mine.id))
        assertEquals(otherBefore, rowsOf(other.id))
        assertNull(store.findVisitor("a".repeat(64)))
        assertEquals(2, store.attemptsOf(other.id).size)
    }

    @Test
    fun `보관 삭제는 기준 날짜보다 앞선 마지막 방문만 지우고 기준 당일은 남긴다`() {
        val cutoff = LocalDate.of(2025, 11, 25)
        val expired = visitor("a".repeat(64), lastVisitOn = cutoff.minusDays(1))
        val onCutoff = visitor("b".repeat(64), lastVisitOn = cutoff)
        val recent = visitor("c".repeat(64), lastVisitOn = cutoff.plusDays(1))
        listOf(expired, onCutoff, recent).forEach { owner ->
            store.recordAttempt(owner.id, submission(), now).also { store.recordGrade(owner.id, it!!.attemptId, Grade.CORRECT, now) }
        }
        val keptBefore = listOf(onCutoff, recent).map { rowsOf(it.id) }

        assertEquals(1, store.deleteVisitorsLastVisitedBefore(cutoff, 500))

        assertEquals(Triple(0, 0, 0), rowsOf(expired.id))
        assertEquals(keptBefore, listOf(onCutoff, recent).map { rowsOf(it.id) })
    }

    @Test
    fun `두 자리가 함께 보관 삭제를 돌려도 결과가 같고 실패하지 않는다`() {
        val cutoff = LocalDate.of(2025, 11, 25)
        val expired = (0 until 20).map { visitor("x$it".padEnd(64, 'x'), lastVisitOn = cutoff.minusDays(it + 1L)) }
        expired.forEach { owner -> repeat(2) { store.recordAttempt(owner.id, submission(), now) } }
        val kept = visitor("k".repeat(64), lastVisitOn = cutoff)
        store.recordAttempt(kept.id, submission(), now)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = listOf(store, greenStore).map { slot -> executor.submit(Callable { start.await(); slot.deleteVisitorsLastVisitedBefore(cutoff, 500) }) }
            start.countDown()
            val deleted = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(20, deleted.sum(), deleted.toString())
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, count("SELECT count(*) FROM study_visitor"))
        assertEquals(Triple(1, 1, 0), rowsOf(kept.id))
        assertEquals(1, count("SELECT count(*) FROM quiz_attempt"))
    }

    // ── 리뷰 루프 1 반영(명세 15) ─────────────────────────

    @Test
    fun `첫 시도는 방문자 줄과 함께 한 묶음으로 들어간다`() {
        val submitted = submission(answer = "첫 답")
        val stored = store.recordFirstAttempt("f".repeat(64), today, submitted, now)
        assertEquals("첫 답", stored?.answer)
        val created = requireNotNull(store.findVisitor("f".repeat(64)))
        assertEquals(today, created.lastVisitOn)
        assertEquals(listOf(summaryOf(stored!!)), store.attemptsOf(created.id))
    }

    @Test
    fun `첫 시도의 번호가 남의 것이면 방문자 줄을 남기지 않고 없음으로 돌아온다`() {
        val owner = visitor("a".repeat(64))
        val attemptId = UUID.randomUUID()
        store.recordAttempt(owner.id, submission(answer = "주인의 답", attemptId = attemptId), now)
        assertNull(store.recordFirstAttempt("f".repeat(64), today, submission(answer = "남의 답", attemptId = attemptId), now))
        assertNull(store.findVisitor("f".repeat(64)))
        assertEquals(1, count("SELECT count(*) FROM study_visitor"))
        assertEquals(listOf(attemptId), store.attemptsOf(owner.id).map { it.attemptId })
        assertEquals("주인의 답", store.findAttempt(owner.id, attemptId)?.answer)
    }

    @Test
    fun `첫 시도 넣기가 DB 오류면 방문자 줄도 남지 않고 저장소 오류로 던진다`() {
        jdbc.execute("CREATE FUNCTION reject_attempt() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'rejected by test'; END \$\$")
        jdbc.execute("CREATE TRIGGER reject_attempt BEFORE INSERT ON quiz_attempt FOR EACH ROW EXECUTE FUNCTION reject_attempt()")
        assertThrows(org.springframework.dao.DataAccessException::class.java) {
            store.recordFirstAttempt("f".repeat(64), today, submission(), now)
        }
        assertEquals(0, count("SELECT count(*) FROM study_visitor"))
    }

    @Test
    fun `보관 삭제의 한 묶음은 한도만큼만 지우고 남은 수를 셀 수 있다`() {
        val cutoff = LocalDate.of(2025, 11, 25)
        val expired = (0 until 8).map { visitor("x$it".padEnd(64, 'x'), lastVisitOn = cutoff.minusDays(it + 1L)) }
        expired.forEach { store.recordAttempt(it.id, submission(), now) }
        val onCutoff = visitor("c".repeat(64), lastVisitOn = cutoff)

        assertEquals(8, store.countVisitorsLastVisitedBefore(cutoff))
        assertEquals(3, store.deleteVisitorsLastVisitedBefore(cutoff, 3))
        assertEquals(5, store.countVisitorsLastVisitedBefore(cutoff))
        assertEquals(3, store.deleteVisitorsLastVisitedBefore(cutoff, 3))
        assertEquals(2, store.deleteVisitorsLastVisitedBefore(cutoff, 3))
        assertEquals(0, store.deleteVisitorsLastVisitedBefore(cutoff, 3))
        assertEquals(0, store.countVisitorsLastVisitedBefore(cutoff))
        assertEquals(listOf(onCutoff.id), jdbc.queryForList("SELECT id FROM study_visitor", Long::class.java))
        assertEquals(0, count("SELECT count(*) FROM quiz_attempt"))
    }

    // ── 리뷰 루프 2 반영(명세 16) ─────────────────────────

    /** 실제 연결을 감싸 DB 로 보낸 SQL 글을 모은다(PreparedStatement · Statement 모두) */
    private class SqlRecordingDataSource(private val delegate: javax.sql.DataSource) : org.springframework.jdbc.datasource.AbstractDataSource() {
        val statements = java.util.concurrent.CopyOnWriteArrayList<String>()

        private fun <T : Any> recording(target: T, type: Class<T>): T =
            type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                val text = arguments?.firstOrNull() as? String
                if (text != null && (method.name.startsWith("prepare") || method.name.startsWith("execute") || method.name == "addBatch" ||
                        method.name == "nativeSQL")) statements += text
                val result = try { method.invoke(target, *(arguments ?: emptyArray())) } catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                if (method.name == "createStatement" && result is java.sql.Statement) recording(result, java.sql.Statement::class.java) else result
            })

        override fun getConnection(): java.sql.Connection = recording(delegate.connection, java.sql.Connection::class.java)
        override fun getConnection(username: String?, password: String?): java.sql.Connection = getConnection()
    }

    @Test
    fun `시도 목록은 답 본문 칸을 읽지 않는다 - 본문이 큰 줄이 있어도 질의 글에 answer 가 없다`() {
        val owner = visitor("h".repeat(64))
        val big = submission(answer = "가".repeat(200_000))
        store.recordAttempt(owner.id, big, now)
        store.recordGrade(owner.id, big.attemptId, Grade.CORRECT, now.plusSeconds(5))

        val recording = SqlRecordingDataSource(blue)
        val recordingStore = ProgressStore(JdbcTemplate(recording))
        recordingStore.createSchema()
        recording.statements.clear()
        val listed = recordingStore.attemptsOf(owner.id)

        assertEquals(listOf(big.attemptId), listed.map { it.attemptId })
        assertEquals(listOf(Grade.CORRECT), listed.map { it.grade })
        assertTrue(recording.statements.isNotEmpty(), "시도 목록이 DB 에 아무 SQL 도 보내지 않았다")
        recording.statements.forEach { sql ->
            assertFalse(sql.contains("answer", ignoreCase = true), "답 본문 칸을 읽는 질의: $sql")
            assertFalse(Regex("""\*\s*(,|FROM)|\.\*""", RegexOption.IGNORE_CASE).containsMatchIn(sql), "모든 칸을 읽는 질의: $sql")
        }
    }
}
