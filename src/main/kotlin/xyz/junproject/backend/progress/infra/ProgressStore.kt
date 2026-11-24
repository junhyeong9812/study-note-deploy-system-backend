package xyz.junproject.backend.progress.infra

import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.ProgressRecords
import xyz.junproject.backend.progress.usecase.StoredAttempt
import xyz.junproject.backend.progress.usecase.Visitor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** PostgreSQL 저장소 — 표 study_visitor · quiz_attempt · quiz_grade */
class ProgressStore(private val jdbc: JdbcTemplate) : ProgressRecords {
    override fun createSchema(): Unit = TODO("task 02")
    override fun findVisitor(tokenHash: String): Visitor? = TODO("task 02")
    override fun createVisitor(tokenHash: String, today: LocalDate, now: Instant): Visitor = TODO("task 02")
    override fun touchVisit(visitorId: Long, today: LocalDate): Unit = TODO("task 02")
    override fun recordAttempt(visitorId: Long, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? = TODO("task 02")
    override fun recordGrade(visitorId: Long, attemptId: UUID, grade: Grade, gradedAt: Instant): StoredAttempt? = TODO("task 02")
    override fun attemptsOf(visitorId: Long): List<StoredAttempt> = TODO("task 02")
    override fun countAttemptsBetween(visitorId: Long, from: Instant, until: Instant): Int = TODO("task 02")
    override fun countAttempts(visitorId: Long): Int = TODO("task 02")
    override fun deleteVisitor(visitorId: Long): Int = TODO("task 02")
    override fun deleteVisitorsLastVisitedBefore(cutoff: LocalDate): Int = TODO("task 02")
}
