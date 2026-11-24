package xyz.junproject.backend.progress.usecase

import org.springframework.dao.DataAccessResourceFailureException
import xyz.junproject.backend.progress.domain.Grade
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 메모리 저장소 — [ProgressRecords] 의 계약(같은 번호는 한 줄 · 첫 채점이 남음 · 남의 것은 null)을 그대로 흉내 낸다.
 * [failing] 에 든 함수 이름은 DB 오류(DataAccessException)로 실패한다. 부른 것은 [calls] 에 남는다.
 */
class FakeProgressRecords : ProgressRecords {
    val failing = mutableSetOf<String>()
    val calls = mutableListOf<String>()
    val visitorsByHash = linkedMapOf<String, Visitor>()
    val attemptOwners = linkedMapOf<UUID, Long>()
    val attempts = linkedMapOf<UUID, StoredAttempt>()
    val touches = mutableListOf<Pair<Long, LocalDate>>()
    val purgeCutoffs = mutableListOf<LocalDate>()
    private var nextVisitorId = 1L

    private fun call(name: String) {
        calls += name
        if (name in failing) throw DataAccessResourceFailureException("connection refused (fake)")
    }

    override fun createSchema() = call("createSchema")

    override fun findVisitor(tokenHash: String): Visitor? { call("findVisitor"); return visitorsByHash[tokenHash] }

    override fun createVisitor(tokenHash: String, today: LocalDate, now: Instant): Visitor {
        call("createVisitor")
        return Visitor(nextVisitorId++, today).also { visitorsByHash[tokenHash] = it }
    }

    override fun touchVisit(visitorId: Long, today: LocalDate) {
        call("touchVisit")
        touches += visitorId to today
        visitorsByHash.entries.first { it.value.id == visitorId }.let { it.setValue(it.value.copy(lastVisitOn = today)) }
    }

    override fun recordAttempt(visitorId: Long, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? {
        call("recordAttempt")
        attemptOwners[submission.attemptId]?.let { owner -> return if (owner == visitorId) attempts[submission.attemptId] else null }
        return put(visitorId, submission, submittedAt)
    }

    /** 테스트 준비 — 부른 것으로 세지 않고 바로 넣는다 */
    fun put(visitorId: Long, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt {
        val stored = StoredAttempt(submission.attemptId, submission.topicPath, submission.questionNumber, submission.answer,
            submission.contentSha, submission.questionHash, submittedAt, null, null)
        attemptOwners[submission.attemptId] = visitorId
        attempts[submission.attemptId] = stored
        return stored
    }

    override fun recordGrade(visitorId: Long, attemptId: UUID, grade: Grade, gradedAt: Instant): StoredAttempt? {
        call("recordGrade")
        if (attemptOwners[attemptId] != visitorId) return null
        val stored = attempts.getValue(attemptId)
        if (stored.grade != null) return stored
        return stored.copy(grade = grade, gradedAt = gradedAt).also { attempts[attemptId] = it }
    }

    private fun ownedBy(visitorId: Long) = attempts.values.filter { attemptOwners[it.attemptId] == visitorId }

    override fun attemptsOf(visitorId: Long): List<StoredAttempt> { call("attemptsOf"); return ownedBy(visitorId).sortedBy { it.submittedAt } }

    override fun countAttemptsBetween(visitorId: Long, from: Instant, until: Instant): Int {
        call("countAttemptsBetween")
        return ownedBy(visitorId).count { !it.submittedAt.isBefore(from) && it.submittedAt.isBefore(until) }
    }

    override fun countAttempts(visitorId: Long): Int { call("countAttempts"); return ownedBy(visitorId).size }

    override fun deleteVisitor(visitorId: Long): Int {
        call("deleteVisitor")
        val owned = ownedBy(visitorId)
        owned.forEach { attempts.remove(it.attemptId); attemptOwners.remove(it.attemptId) }
        visitorsByHash.entries.removeIf { it.value.id == visitorId }
        return owned.size
    }

    override fun deleteVisitorsLastVisitedBefore(cutoff: LocalDate): Int {
        call("deleteVisitorsLastVisitedBefore")
        purgeCutoffs += cutoff
        val expired = visitorsByHash.values.filter { it.lastVisitOn.isBefore(cutoff) }
        expired.forEach { deleteVisitor(it.id) }
        return expired.size
    }
}
