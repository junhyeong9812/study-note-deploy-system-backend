package xyz.junproject.backend.progress.infra

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.ProgressRecords
import xyz.junproject.backend.progress.usecase.StoredAttempt
import xyz.junproject.backend.progress.usecase.Visitor
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL 저장소 — 표 study_visitor · quiz_attempt · quiz_grade.
 * 표는 처음 닿을 때 만든다(만들기에 성공하기 전까지는 호출마다 다시 시도한다) — 뜬 직후의 미리 만들기는 설정이 부른다.
 * 시도 · 채점은 덧붙이기만 한다: 같은 번호는 `ON CONFLICT DO NOTHING` 뒤 다시 읽는다(고치지 않는다).
 */
class ProgressStore(
    private val jdbc: JdbcTemplate,
    /** DB 에 닿지 못한 뒤의 쉼을 재는 시계(명세 15) */
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** DB 에 닿지 못한 직후 이 동안은 풀을 기다리지 않고 곧바로 DataAccessException 으로 실패한다(명세 15 — 짧은 쉼) */
    private val unavailablePause: java.time.Duration = java.time.Duration.ofSeconds(5),
) : ProgressRecords {
    private companion object {
        /** 표 만들기를 줄 세우는 잠금 번호 — 두 자리가 동시에 `CREATE ... IF NOT EXISTS` 를 돌리면 카탈로그 키 충돌로 한쪽이 깨진다(실측) */
        const val SCHEMA_LOCK = 7_310_250_001L
        /** 보관 삭제를 줄 세우는 잠금 번호 — 두 자리가 같은 줄들을 지우며 서로 기다리다 교착하지 않게 */
        const val PURGE_LOCK = 7_310_250_002L

        val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS study_visitor (
                id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                token_hash    TEXT NOT NULL UNIQUE,
                account_id    BIGINT,
                last_visit_on DATE NOT NULL,
                created_at    TIMESTAMPTZ NOT NULL
            )
            """,
            "COMMENT ON COLUMN study_visitor.account_id IS '로그인 계정의 자리 — 지금은 늘 비어 있다'",
            "CREATE INDEX IF NOT EXISTS study_visitor_last_visit_on ON study_visitor (last_visit_on)",
            """
            CREATE TABLE IF NOT EXISTS quiz_attempt (
                attempt_id      UUID PRIMARY KEY,
                visitor_id      BIGINT NOT NULL REFERENCES study_visitor (id) ON DELETE CASCADE,
                topic_path      TEXT NOT NULL,
                question_number INT NOT NULL,
                answer          TEXT NOT NULL,
                content_sha     TEXT NOT NULL,
                question_hash   TEXT NOT NULL,
                submitted_at    TIMESTAMPTZ NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS quiz_attempt_visitor_submitted ON quiz_attempt (visitor_id, submitted_at)",
            """
            CREATE TABLE IF NOT EXISTS quiz_grade (
                attempt_id UUID PRIMARY KEY REFERENCES quiz_attempt (attempt_id) ON DELETE CASCADE,
                grade      TEXT NOT NULL CHECK (grade IN ('CORRECT', 'PARTIAL', 'WRONG')),
                graded_at  TIMESTAMPTZ NOT NULL
            )
            """,
        )

        const val ATTEMPT_COLUMNS = """
            a.attempt_id, a.topic_path, a.question_number, a.answer, a.content_sha, a.question_hash, a.submitted_at, g.grade, g.graded_at
            FROM quiz_attempt a LEFT JOIN quiz_grade g ON g.attempt_id = a.attempt_id"""

        val attemptRow = RowMapper { row, _ ->
            StoredAttempt(
                attemptId = row.getObject("attempt_id", UUID::class.java),
                topicPath = row.getString("topic_path"),
                questionNumber = row.getInt("question_number"),
                answer = row.getString("answer"),
                contentSha = row.getString("content_sha"),
                questionHash = row.getString("question_hash"),
                submittedAt = row.getObject("submitted_at", OffsetDateTime::class.java).toInstant(),
                grade = row.getString("grade")?.let(Grade::valueOf),
                gradedAt = row.getObject("graded_at", OffsetDateTime::class.java)?.toInstant(),
            )
        }

        val visitorRow = RowMapper { row, _ -> Visitor(row.getLong("id"), row.getObject("last_visit_on", LocalDate::class.java)) }

        fun timestamp(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
    }

    @Volatile private var schemaReady = false
    private val creating = Any()

    /** 표가 있는 것을 확인한 뒤의 JdbcTemplate — 아직이면 여기서 만든다(실패는 DataAccessException 그대로) */
    private fun ready(): JdbcTemplate {
        if (!schemaReady) synchronized(creating) { if (!schemaReady) createSchema() }
        return jdbc
    }

    /** 한 트랜잭션 안에서 [lock] 을 잡고 [work] 를 돈다 — 잠금은 트랜잭션이 끝나면 풀린다 */
    private fun <T> underLock(target: JdbcTemplate, lock: Long, work: (java.sql.Connection) -> T): T =
        target.execute(ConnectionCallback { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, lock)
                    statement.executeQuery().close()
                }
                work(connection).also { connection.commit() }
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                connection.autoCommit = autoCommit
            }
        })!!

    override fun createSchema() {
        underLock(jdbc, SCHEMA_LOCK) { connection ->
            connection.createStatement().use { statement -> SCHEMA.forEach { statement.execute(it.trimIndent()) } }
        }
        schemaReady = true
    }

    override fun findVisitor(tokenHash: String): Visitor? =
        ready().query("SELECT id, last_visit_on FROM study_visitor WHERE token_hash = ?", visitorRow, tokenHash).singleOrNull()

    override fun createVisitor(tokenHash: String, today: LocalDate, now: Instant): Visitor =
        ready().queryForObject(
            "INSERT INTO study_visitor (token_hash, last_visit_on, created_at) VALUES (?, ?, ?) RETURNING id, last_visit_on",
            visitorRow, tokenHash, today, timestamp(now))

    override fun touchVisit(visitorId: Long, today: LocalDate) {
        ready().update("UPDATE study_visitor SET last_visit_on = ? WHERE id = ? AND last_visit_on < ?", today, visitorId, today)
    }

    override fun recordAttempt(visitorId: Long, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? {
        val database = ready()
        database.update(
            """
            INSERT INTO quiz_attempt (attempt_id, visitor_id, topic_path, question_number, answer, content_sha, question_hash, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (attempt_id) DO NOTHING
            """.trimIndent(),
            submission.attemptId, visitorId, submission.topicPath, submission.questionNumber, submission.answer,
            submission.contentSha, submission.questionHash, timestamp(submittedAt))
        // 새로 넣었든 이미 있었든 저장된 줄을 다시 읽는다 — 동시에 넣은 쪽들이 모두 같은 줄을 받는다. 남의 줄이면 null
        return findAttempt(visitorId, submission.attemptId)
    }

    override fun findAttempt(visitorId: Long, attemptId: UUID): StoredAttempt? =
        ready().query("SELECT $ATTEMPT_COLUMNS WHERE a.attempt_id = ? AND a.visitor_id = ?", attemptRow, attemptId, visitorId).singleOrNull()

    override fun recordGrade(visitorId: Long, attemptId: UUID, grade: Grade, gradedAt: Instant): StoredAttempt? {
        val database = ready()
        database.update(
            """
            INSERT INTO quiz_grade (attempt_id, grade, graded_at)
            SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM quiz_attempt WHERE attempt_id = ? AND visitor_id = ?)
            ON CONFLICT (attempt_id) DO NOTHING
            """.trimIndent(),
            attemptId, grade.name, timestamp(gradedAt), attemptId, visitorId)
        return findAttempt(visitorId, attemptId)
    }

    override fun attemptsOf(visitorId: Long): List<StoredAttempt> =
        ready().query("SELECT $ATTEMPT_COLUMNS WHERE a.visitor_id = ? ORDER BY a.submitted_at, a.attempt_id", attemptRow, visitorId)

    override fun countAttemptsBetween(visitorId: Long, from: Instant, until: Instant): Int =
        ready().queryForObject(
            "SELECT count(*) FROM quiz_attempt WHERE visitor_id = ? AND submitted_at >= ? AND submitted_at < ?",
            Int::class.java, visitorId, timestamp(from), timestamp(until))!!

    override fun countAttempts(visitorId: Long): Int =
        ready().queryForObject("SELECT count(*) FROM quiz_attempt WHERE visitor_id = ?", Int::class.java, visitorId)!!

    /** 한 문장 — 바깥 SELECT 는 지우기 전의 스냅숏을 보므로 지운 방문자의 시도 수가 나온다(시도 · 채점은 ON DELETE CASCADE) */
    override fun deleteVisitor(visitorId: Long): Int =
        ready().queryForObject(
            """
            WITH removed AS (DELETE FROM study_visitor WHERE id = ? RETURNING id)
            SELECT count(*) FROM quiz_attempt WHERE visitor_id IN (SELECT id FROM removed)
            """.trimIndent(),
            Int::class.java, visitorId)!!

    /** 두 자리가 함께 돌려도 잠금으로 한 줄로 선다 — 뒤에 선 쪽은 남은 것만(대개 0) 지우므로 결과가 같다 */
    override fun recordFirstAttempt(tokenHash: String, today: LocalDate, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? =
        TODO("task 02 리뷰 루프 1")

    override fun countVisitorsLastVisitedBefore(cutoff: LocalDate): Int = TODO("task 02 리뷰 루프 1")

    override fun deleteVisitorsLastVisitedBefore(cutoff: LocalDate, limit: Int): Int =
        underLock(ready(), PURGE_LOCK) { connection ->
            connection.prepareStatement("DELETE FROM study_visitor WHERE last_visit_on < ?").use { statement ->
                statement.setObject(1, cutoff)
                statement.executeUpdate()
            }
        }
}
