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

    override fun findAttempt(visitorId: Long, attemptId: UUID): StoredAttempt? {
        call("findAttempt")
        return if (attemptOwners[attemptId] == visitorId) attempts[attemptId] else null
    }

    override fun recordGrade(visitorId: Long, attemptId: UUID, grade: Grade, gradedAt: Instant): StoredAttempt? {
        call("recordGrade")
        if (attemptOwners[attemptId] != visitorId) return null
        val stored = attempts.getValue(attemptId)
        if (stored.grade != null) return stored
        return stored.copy(grade = grade, gradedAt = gradedAt).also { attempts[attemptId] = it }
    }

    private fun ownedBy(visitorId: Long) = attempts.values.filter { attemptOwners[it.attemptId] == visitorId }

    override fun attemptsOf(visitorId: Long): List<AttemptSummary> {
        call("attemptsOf")
        return ownedBy(visitorId).sortedBy { it.submittedAt }.map(::summaryOf)
    }

    /** 주제 단위 읽기의 인자(방문자 · 주제) — 읽는 시도가 그 주제의 것만인지 본다(명세 18.2) */
    val topicReads = mutableListOf<Pair<Long, String>>()
    /** 질문 하나 단위 읽기의 인자(방문자 · 주제 · 번호) */
    val questionReads = mutableListOf<Triple<Long, String, Int>>()
    /** 최근 시도 읽기가 받은 한도 */
    val recentReadLimits = mutableListOf<Int>()

    override fun attemptsOfTopic(visitorId: Long, topicPath: String): List<AttemptSummary> {
        call("attemptsOfTopic")
        topicReads += visitorId to topicPath
        return ownedBy(visitorId).filter { it.topicPath == topicPath }.sortedBy { it.submittedAt }.map(::summaryOf)
    }

    override fun attemptsOfQuestion(visitorId: Long, topicPath: String, questionNumber: Int): List<StoredAttempt> {
        call("attemptsOfQuestion")
        questionReads += Triple(visitorId, topicPath, questionNumber)
        return ownedBy(visitorId).filter { it.topicPath == topicPath && it.questionNumber == questionNumber }.sortedBy { it.submittedAt }
    }

    override fun recentAttemptsOf(visitorId: Long, limit: Int): List<AttemptSummary> {
        call("recentAttemptsOf")
        recentReadLimits += limit
        return ownedBy(visitorId).sortedBy { it.submittedAt }.takeLast(limit).map(::summaryOf)
    }

    /** 테스트 준비 — 그 시도에 채점을 바로 붙인다(부른 것으로 세지 않는다) */
    fun putGrade(attemptId: UUID, grade: Grade, gradedAt: Instant) {
        attempts[attemptId] = attempts.getValue(attemptId).copy(grade = grade, gradedAt = gradedAt)
    }

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

    override fun recordFirstAttempt(tokenHash: String, today: LocalDate, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt? {
        call("recordFirstAttempt")
        if (attemptOwners.containsKey(submission.attemptId)) return null      // 한 묶음 — 방문자도 남기지 않는다
        val visitor = Visitor(nextVisitorId++, today).also { visitorsByHash[tokenHash] = it }
        return put(visitor.id, submission, submittedAt)
    }

    /** 몇 번째 묶음(1부터)에서 DB 오류로 실패할지 — null 이면 실패하지 않는다 */
    var purgeFailAtBatch: Int? = null

    /** 보관 삭제의 묶음마다 받은 한도 */
    val purgeLimits = mutableListOf<Int>()

    override fun deleteVisitorsLastVisitedBefore(cutoff: LocalDate, limit: Int): Int {
        call("deleteVisitorsLastVisitedBefore")
        purgeCutoffs += cutoff
        purgeLimits += limit
        if (purgeFailAtBatch == purgeLimits.size) throw DataAccessResourceFailureException("connection lost mid purge (fake)")
        val expired = visitorsByHash.values.filter { it.lastVisitOn.isBefore(cutoff) }.take(limit)
        expired.forEach { visitor ->
            ownedBy(visitor.id).forEach { attempts.remove(it.attemptId); attemptOwners.remove(it.attemptId) }
            visitorsByHash.entries.removeIf { it.value.id == visitor.id }
        }
        return expired.size
    }

    override fun countVisitorsLastVisitedBefore(cutoff: LocalDate): Int {
        call("countVisitorsLastVisitedBefore")
        return visitorsByHash.values.count { it.lastVisitOn.isBefore(cutoff) }
    }
}

/** 저장된 시도에서 본문을 뺀 상태 계산용 줄 — 테스트가 기대값을 만들 때 쓴다 */
fun summaryOf(stored: StoredAttempt) = AttemptSummary(stored.attemptId, stored.topicPath, stored.questionNumber, stored.contentSha,
    stored.questionHash, stored.submittedAt, stored.grade, stored.gradedAt)
