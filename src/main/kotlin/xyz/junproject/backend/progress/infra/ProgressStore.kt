package xyz.junproject.backend.progress.infra

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.ProgressRecords
import xyz.junproject.backend.progress.usecase.StoredAttempt
import xyz.junproject.backend.progress.usecase.Visitor
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL 저장소 — 표 study_visitor · quiz_attempt · quiz_grade.
 * 표는 처음 닿을 때 만든다(만들기에 성공하기 전까지는 호출마다 다시 시도한다) — 뜬 직후의 미리 만들기는 설정이 부른다.
 * DB 에서 연결을 얻지 못하면 [unavailablePause] 동안은 DB 에 닿아 보지 않고 곧바로 실패한다(명세 15) — 요청마다 풀 대기(2초)를 치르지 않게.
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
    /** 연결을 얻지 못한 때부터 [unavailablePause] 뒤 — 그 전까지는 DB 에 닿아 보지 않는다. null 이면 쉬지 않는다 */
    @Volatile private var pausedUntil: Instant? = null

    /**
     * DB 에 닿는 모든 일의 입구 — 쉼 동안이면 곧바로 실패하고(쉼을 늘리지 않는다), 연결을 얻지 못하면 쉼을 건다.
     * 쉼은 "연결을 얻지 못함"에만 건다 — 키 충돌 같은 SQL 오류에 걸면 잘못된 요청 하나가 모든 기록 요청을 막는다.
     */
    private fun <T> guarded(work: () -> T): T {
        pausedUntil?.let { until ->
            if (clock.instant().isBefore(until)) throw DataAccessResourceFailureException("study db unreachable recently — not retrying yet")
        }
        try {
            return work()
        } catch (error: CannotGetJdbcConnectionException) {
            pausedUntil = clock.instant().plus(unavailablePause)
            throw error
        }
    }

    /**
     * 표가 있는 것을 확인한 뒤 [work] 를 돈다 — 아직이면 여기서 만든다(실패는 DataAccessException 그대로).
     * 잠금을 쥐지 않는다: 잠금을 쥔 채 풀을 기다리면 가상 스레드의 실행 자리를 붙잡아 무관한 요청이 밀린다(실측 5.7초).
     * 여러 호출이 함께 만들어도 DB 의 advisory lock 이 줄 세우고 `IF NOT EXISTS` 라 결과는 같다.
     */
    private fun <T> reach(work: (JdbcTemplate) -> T): T = guarded {
        if (!schemaReady) createTables()
        work(jdbc)
    }

    /** 한 트랜잭션 안에서 [work] 를 돈다 — 예외면 되돌린다. [work] 가 스스로 되돌린 뒤의 commit 은 아무것도 남기지 않는다 */
    @Suppress("UNCHECKED_CAST")   // execute 는 T? 를 돌려준다 — T 가 null 을 허용하는 형(첫 시도의 없음)이면 null 도 그대로 돌려준다
    private fun <T> inTransaction(target: JdbcTemplate, work: (Connection) -> T): T =
        target.execute(ConnectionCallback { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                work(connection).also { connection.commit() }
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                connection.autoCommit = autoCommit
            }
        }) as T

    /** 한 트랜잭션 안에서 [lock] 을 잡고 [work] 를 돈다 — 잠금은 트랜잭션이 끝나면 풀린다 */
    private fun <T> underLock(target: JdbcTemplate, lock: Long, work: (Connection) -> T): T =
        inTransaction(target) { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, lock)
                statement.executeQuery().close()
            }
            work(connection)
        }

    private fun createTables() {
        underLock(jdbc, SCHEMA_LOCK) { connection ->
            connection.createStatement().use { statement -> SCHEMA.forEach { statement.execute(it.trimIndent()) } }
        }
        schemaReady = true
    }

    override fun createSchema() = guarded { createTables() }

    override fun findVisitor(tokenHash: String): Visitor? =
        reach { database -> database.query("SELECT id, last_visit_on FROM study_visitor WHERE token_hash = ?", visitorRow, tokenHash).singleOrNull() }

    override fun createVisitor(tokenHash: String, today: LocalDate, now: Instant): Visitor =
        reach { database -> database.queryForObject(
            "INSERT INTO study_visitor (token_hash, last_visit_on, created_at) VALUES (?, ?, ?) RETURNING id, last_visit_on",
            visitorRow, tokenHash, today, timestamp(now)) }

    override fun touchVisit(visitorId: Long, today: LocalDate) {
        reach { database -> database.update("UPDATE study_visitor SET last_visit_on = ? WHERE id = ? AND last_visit_on < ?", today, visitorId, today) }
    }

    override fun recordAttempt(visitorId: Long, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? {
        reach { database -> database.update(
            """
            INSERT INTO quiz_attempt (attempt_id, visitor_id, topic_path, question_number, answer, content_sha, question_hash, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (attempt_id) DO NOTHING
            """.trimIndent(),
            submission.attemptId, visitorId, submission.topicPath, submission.questionNumber, submission.answer,
            submission.contentSha, submission.questionHash, timestamp(submittedAt)) }
        // 새로 넣었든 이미 있었든 저장된 줄을 다시 읽는다 — 동시에 넣은 쪽들이 모두 같은 줄을 받는다. 남의 줄이면 null
        return findAttempt(visitorId, submission.attemptId)
    }

    override fun findAttempt(visitorId: Long, attemptId: UUID): StoredAttempt? =
        reach { database -> database.query("SELECT $ATTEMPT_COLUMNS WHERE a.attempt_id = ? AND a.visitor_id = ?", attemptRow, attemptId, visitorId).singleOrNull() }

    override fun recordGrade(visitorId: Long, attemptId: UUID, grade: Grade, gradedAt: Instant): StoredAttempt? {
        reach { database -> database.update(
            """
            INSERT INTO quiz_grade (attempt_id, grade, graded_at)
            SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM quiz_attempt WHERE attempt_id = ? AND visitor_id = ?)
            ON CONFLICT (attempt_id) DO NOTHING
            """.trimIndent(),
            attemptId, grade.name, timestamp(gradedAt), attemptId, visitorId) }
        return findAttempt(visitorId, attemptId)
    }

    override fun attemptsOf(visitorId: Long): List<StoredAttempt> =
        reach { database -> database.query("SELECT $ATTEMPT_COLUMNS WHERE a.visitor_id = ? ORDER BY a.submitted_at, a.attempt_id", attemptRow, visitorId) }

    override fun countAttemptsBetween(visitorId: Long, from: Instant, until: Instant): Int =
        reach { database -> database.queryForObject(
            "SELECT count(*) FROM quiz_attempt WHERE visitor_id = ? AND submitted_at >= ? AND submitted_at < ?",
            Int::class.java, visitorId, timestamp(from), timestamp(until))!! }

    override fun countAttempts(visitorId: Long): Int =
        reach { database -> database.queryForObject("SELECT count(*) FROM quiz_attempt WHERE visitor_id = ?", Int::class.java, visitorId)!! }

    /** 한 문장 — 바깥 SELECT 는 지우기 전의 스냅숏을 보므로 지운 방문자의 시도 수가 나온다(시도 · 채점은 ON DELETE CASCADE) */
    override fun deleteVisitor(visitorId: Long): Int =
        reach { database -> database.queryForObject(
            """
            WITH removed AS (DELETE FROM study_visitor WHERE id = ? RETURNING id)
            SELECT count(*) FROM quiz_attempt WHERE visitor_id IN (SELECT id FROM removed)
            """.trimIndent(),
            Int::class.java, visitorId)!! }

    /**
     * 첫 답 — 방문자 줄과 시도를 한 트랜잭션으로(명세 15). 시도 번호가 남의 것이면(동시에 넣는 중인 남의 줄은 그 끝을 기다린다)
     * 되돌려 방문자 줄도 남기지 않고 null. DB 오류면 되돌려지고 DataAccessException.
     */
    override fun recordFirstAttempt(tokenHash: String, today: LocalDate, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? =
        reach { database ->
            inTransaction(database) { connection ->
                val visitorId = connection.prepareStatement(
                    "INSERT INTO study_visitor (token_hash, last_visit_on, created_at) VALUES (?, ?, ?) RETURNING id",
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.setObject(2, today)
                    statement.setObject(3, timestamp(submittedAt))
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                connection.prepareStatement(
                    """
                    INSERT INTO quiz_attempt (attempt_id, visitor_id, topic_path, question_number, answer, content_sha, question_hash, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (attempt_id) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, submission.attemptId)
                    statement.setLong(2, visitorId)
                    statement.setString(3, submission.topicPath)
                    statement.setInt(4, submission.questionNumber)
                    statement.setString(5, submission.answer)
                    statement.setString(6, submission.contentSha)
                    statement.setString(7, submission.questionHash)
                    statement.setObject(8, timestamp(submittedAt))
                    statement.executeUpdate()
                }
                val stored = connection.prepareStatement("SELECT $ATTEMPT_COLUMNS WHERE a.attempt_id = ? AND a.visitor_id = ?").use { statement ->
                    statement.setObject(1, submission.attemptId)
                    statement.setLong(2, visitorId)
                    statement.executeQuery().use { result -> if (result.next()) attemptRow.mapRow(result, 0) else null }
                }
                if (stored == null) connection.rollback()        // 남의 번호 — 방금 만든 방문자 줄까지 되돌린다
                stored
            }
        }

    override fun countVisitorsLastVisitedBefore(cutoff: LocalDate): Int =
        reach { database -> database.queryForObject("SELECT count(*) FROM study_visitor WHERE last_visit_on < ?", Int::class.java, cutoff)!! }

    /** 한 묶음([limit] 명)만 지운다. 두 자리가 함께 돌려도 잠금으로 한 줄로 선다 — 뒤에 선 쪽은 남은 것만 지우므로 결과가 같다 */
    override fun deleteVisitorsLastVisitedBefore(cutoff: LocalDate, limit: Int): Int =
        reach { database ->
            underLock(database, PURGE_LOCK) { connection ->
                connection.prepareStatement(
                    "DELETE FROM study_visitor WHERE id IN (SELECT id FROM study_visitor WHERE last_visit_on < ? ORDER BY id LIMIT ?)",
                ).use { statement ->
                    statement.setObject(1, cutoff)
                    statement.setInt(2, limit)
                    statement.executeUpdate()
                }
            }
        }
}
