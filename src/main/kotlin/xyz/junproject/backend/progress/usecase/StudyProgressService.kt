package xyz.junproject.backend.progress.usecase

import org.springframework.dao.DataAccessException
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.domain.ReviewSchedule
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Clock
import java.time.Period
import java.util.UUID

sealed interface VisitorLookup {
    data class Found(val visitor: Visitor) : VisitorLookup
    /** 쿠키 없음 · 꼴이 틀림 · 목록에 없음 */
    data object Unknown : VisitorLookup
    /** DB 오류로 확인하지 못함 — 새 토큰을 발급하지도 쿠키를 지우지도 않는다 */
    data object Unavailable : VisitorLookup
}

sealed interface SubmitOutcome {
    /** cookieToken: 쿠키로 내보낼 토큰 원문 — 새 방문자면 새로 만든 것, 아는 방문자면 제시된 것(명세 15). newVisitor: 이번에 새로 만들었나 */
    data class Saved(val attempt: StoredAttempt, val cookieToken: String, val newVisitor: Boolean) : SubmitOutcome
    /** 저장할 수 없는 글자(NUL) — 저장소에 닿기 전에 거절한다(명세 15) */
    data object InvalidInput : SubmitOutcome
    data object AnswerTooLong : SubmitOutcome
    data object DailyLimitReached : SubmitOutcome
    /** 같은 시도 번호를 다른 방문자가 이미 씀 */
    data object AttemptIdTaken : SubmitOutcome
    /** 모르는 방문자(쿠키 없음 · 모르는 토큰)의 첫 답인데 새 방문자 발급의 상한에 닿음 — 저장하지 않고 토큰도 만들지 않는다(명세 18.2) */
    data object NewVisitorRateLimited : SubmitOutcome
    data object StorageUnavailable : SubmitOutcome
}

sealed interface GradeOutcome {
    data class Graded(val attempt: StoredAttempt) : GradeOutcome
    data object AttemptNotFound : GradeOutcome
    data object VisitorUnknown : GradeOutcome
    data object StorageUnavailable : GradeOutcome
}

sealed interface CountOutcome {
    data class Counted(val attempts: Int) : CountOutcome
    data object StorageUnavailable : CountOutcome
}

sealed interface DeleteOutcome {
    data class Deleted(val attempts: Int) : DeleteOutcome
    data object StorageUnavailable : DeleteOutcome
}

/** 방문자의 시도 목록 — 모르는 방문자는 빈 목록 */
sealed interface AttemptsOutcome {
    data class Listed(val attempts: List<AttemptSummary>) : AttemptsOutcome
    data object StorageUnavailable : AttemptsOutcome
}

/** 방문자의 기록 읽기(명세 15 · 18.2) — 읽음 / 방문자 없음(쿠키 없음 · 모르는 토큰, 방문자를 만들지 않는다) / 저장소 사용 불가 */
sealed interface VisitorRead<out T> {
    data class Read<T>(val value: T) : VisitorRead<T>
    data object NoVisitor : VisitorRead<Nothing>
    data object StorageUnavailable : VisitorRead<Nothing>
}

sealed interface PurgeOutcome {
    /** remaining: 한 실행의 총량 상한에서 멈춰 아직 남은 수(다 지웠으면 0) */
    data class Purged(val visitors: Int, val remaining: Int) : PurgeOutcome
    data object Failed : PurgeOutcome
}

/** 방문자 식별과 공부 기록(명세 2.1 · 2.2 · 11.2). 시각은 주입한 시계, 날짜는 한국 시각 */
class StudyProgressService(
    private val records: ProgressRecords,
    private val clock: Clock,
    private val requestLog: RequestLog,
    /** 보관 삭제의 한 묶음(명세 15 — 기본 500) */
    private val purgeBatchSize: Int = PURGE_BATCH_SIZE,
    /** 보관 삭제 한 실행의 총량 상한(명세 15) */
    private val purgeMaxPerRun: Int = PURGE_MAX_PER_RUN,
    private val issueToken: () -> String = { VisitorToken.issue() },
) {
    companion object {
        const val ANSWER_MAX_CHARS = 4_000
        const val DAILY_ATTEMPT_LIMIT = 1_000
        val RETENTION: Period = Period.ofYears(1)
        const val PURGE_BATCH_SIZE = 500
        /** 한 실행의 총량 상한 — 넘는 만큼은 다음 날의 실행이 이어 지운다 */
        const val PURGE_MAX_PER_RUN = 50_000
    }

    private fun today() = ReviewSchedule.dateOf(clock.instant())

    /** 저장소 오류의 로그 — 예외의 문구는 싣지 않는다(SQL 오류 문구에 값이 섞여 나올 수 있다). 종류만 */
    private fun logStorageFailure(requestId: String, action: String, error: DataAccessException) =
        requestLog.log(requestId, "progress $action failed: ${error.javaClass.simpleName}", "error")

    /**
     * 쿠키의 토큰으로 방문자를 찾는다 — 읽기만 하는 요청은 방문자를 만들지 않는다.
     * 한국 날짜가 바뀐 뒤의 첫 식별에서만 마지막 방문을 갱신하고, 그 실패는 요청을 막지 않는다.
     */
    fun identify(requestId: String, presentedToken: String?): VisitorLookup {
        if (presentedToken == null || !VisitorToken.isWellFormed(presentedToken)) return VisitorLookup.Unknown
        val visitor = try {
            records.findVisitor(VisitorToken.hash(presentedToken))
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "identify", error)
            return VisitorLookup.Unavailable
        } ?: return VisitorLookup.Unknown
        val today = today()
        if (!visitor.lastVisitOn.isBefore(today)) return VisitorLookup.Found(visitor)
        return try {
            records.touchVisit(visitor.id, today)
            VisitorLookup.Found(visitor.copy(lastVisitOn = today))
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "touch visit", error)
            VisitorLookup.Found(visitor)
        }
    }

    /**
     * 답을 덧붙인다. 입력 검사(NUL · 길이)가 먼저(저장소 · 토큰 발급에 닿기 전), 모르는 방문자면 여기서 처음 토큰을 발급하고
     * 방문자 줄과 시도를 한 묶음으로 넣는다(명세 15 — 시도가 저장되지 않으면 방문자 줄도 남지 않는다).
     * 확인 불가(DB 오류)면 발급하지 않는다. 로그에는 주제 · 질문 번호 · 길이 · 결과만 — 답의 본문 · 토큰은 쓰지 않는다.
     * [admitNewVisitor] 는 모르는 방문자로 확인된 뒤에만 부른다(새 방문자 발급의 상한 — 아는 방문자 · 확인 불가는 세지 않는다).
     * 받은 칸은 방문자 줄이 실제로 만들어졌을 때만 쓴다 — 첫 답이 저장되지 않으면(이미 쓰인 시도 번호 · DB 오류) [releaseNewVisitor] 로 돌려준다(명세 20).
     */
    fun submitAnswer(
        requestId: String,
        presentedToken: String?,
        submission: AnswerSubmission,
        admitNewVisitor: () -> Boolean = { true },
        releaseNewVisitor: () -> Unit = {},
    ): SubmitOutcome {
        val subject = "topic=${submission.topicPath} question=${submission.questionNumber}"
        val length = submission.answer.codePointCount(0, submission.answer.length)
        // PostgreSQL 의 글 칸은 NUL 을 담지 못한다 — 저장소 장애가 아니라 잘못된 입력이다(그래서 ERROR 로 남기지 않는다)
        val textFields = listOf(submission.answer, submission.topicPath, submission.contentSha, submission.questionHash)
        if (textFields.any { it.contains('\u0000') }) {
            requestLog.log(requestId, "progress answer rejected(invalid input: NUL) question=${submission.questionNumber} length=$length", "warning")
            return SubmitOutcome.InvalidInput
        }
        if (length > ANSWER_MAX_CHARS) {
            requestLog.log(requestId, "progress answer rejected(too long) $subject length=$length", "warning")
            return SubmitOutcome.AnswerTooLong
        }
        val now = clock.instant()
        val today = ReviewSchedule.dateOf(now)
        try {
            val visitor = when (val lookup = identify(requestId, presentedToken)) {
                is VisitorLookup.Found -> lookup.visitor
                VisitorLookup.Unavailable -> return SubmitOutcome.StorageUnavailable
                VisitorLookup.Unknown -> {
                    if (!admitNewVisitor()) {
                        requestLog.log(requestId, "progress answer rejected(new visitor rate limited) $subject length=$length", "warning")
                        return SubmitOutcome.NewVisitorRateLimited
                    }
                    val token = issueToken()
                    val stored = try {
                        records.recordFirstAttempt(VisitorToken.hash(token), today, submission, now)
                    } catch (error: DataAccessException) {
                        releaseNewVisitor()
                        throw error
                    }
                    if (stored == null) {
                        releaseNewVisitor()
                        requestLog.log(requestId, "progress answer rejected(attempt id taken) $subject newVisitor=true", "warning")
                        return SubmitOutcome.AttemptIdTaken
                    }
                    requestLog.log(requestId, "progress answer saved $subject length=$length newVisitor=true")
                    return SubmitOutcome.Saved(stored, token, newVisitor = true)
                }
            }
            val cookieToken = presentedToken!!      // Found 는 꼴이 맞는 제시된 토큰에서만 나온다
            // 단순화: 세고 나서 넣는다(원자적이 아니다) — 한 방문자의 요청이 동시에 몰리면 상한을 그 동시 수만큼 넘길 수 있다(1,000 에 몇 건).
            // 정확히 막아야 하면 방문자 줄을 잠그고(SELECT ... FOR UPDATE) 세기와 넣기를 한 트랜잭션으로 묶는다.
            val dayStart = today.atStartOfDay(ReviewSchedule.ZONE).toInstant()
            val dayEnd = today.plusDays(1).atStartOfDay(ReviewSchedule.ZONE).toInstant()
            if (records.countAttemptsBetween(visitor.id, dayStart, dayEnd) >= DAILY_ATTEMPT_LIMIT) {
                // 상한에 닿은 뒤에도 이미 저장한 시도의 재전송(두 번 눌림)은 처음 결과를 돌려준다
                records.findAttempt(visitor.id, submission.attemptId)?.let { return SubmitOutcome.Saved(it, cookieToken, newVisitor = false) }
                requestLog.log(requestId, "progress answer rejected(daily limit) $subject length=$length", "warning")
                return SubmitOutcome.DailyLimitReached
            }
            val stored = records.recordAttempt(visitor.id, submission, now)
            if (stored == null) {
                requestLog.log(requestId, "progress answer rejected(attempt id taken) $subject", "warning")
                return SubmitOutcome.AttemptIdTaken
            }
            requestLog.log(requestId, "progress answer saved $subject length=$length newVisitor=false")
            return SubmitOutcome.Saved(stored, cookieToken, newVisitor = false)
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "submit answer($subject)", error)
            return SubmitOutcome.StorageUnavailable
        }
    }

    /** 방문자의 시도 목록(낸 시각 차례) — 쿠키 없음 · 모르는 토큰은 빈 목록이고 방문자를 만들지 않는다. DB 오류는 결과로(명세 15) */
    fun attempts(requestId: String, presentedToken: String?): AttemptsOutcome {
        val visitor = when (val lookup = identify(requestId, presentedToken)) {
            is VisitorLookup.Found -> lookup.visitor
            VisitorLookup.Unknown -> return AttemptsOutcome.Listed(emptyList())
            VisitorLookup.Unavailable -> return AttemptsOutcome.StorageUnavailable
        }
        return try {
            AttemptsOutcome.Listed(records.attemptsOf(visitor.id))
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "list attempts", error)
            AttemptsOutcome.StorageUnavailable
        }
    }

    /** 쿠키의 방문자로 [work] 를 읽는다 — 방문자를 만들지 않고, DB 오류는 결과로(명세 15) */
    private fun <T> readFor(requestId: String, presentedToken: String?, action: String, work: (Visitor) -> T): VisitorRead<T> {
        val visitor = when (val lookup = identify(requestId, presentedToken)) {
            is VisitorLookup.Found -> lookup.visitor
            VisitorLookup.Unknown -> return VisitorRead.NoVisitor
            VisitorLookup.Unavailable -> return VisitorRead.StorageUnavailable
        }
        return try {
            VisitorRead.Read(work(visitor))
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, action, error)
            VisitorRead.StorageUnavailable
        }
    }

    /** 그 주제의 시도만(본문 없이, 낸 시각 차례) — 질문 목록의 상태 계산(명세 18.2) */
    fun attemptsOfTopic(requestId: String, presentedToken: String?, topicPath: String): VisitorRead<List<AttemptSummary>> =
        readFor(requestId, presentedToken, "list topic attempts") { visitor -> records.attemptsOfTopic(visitor.id, topicPath) }

    /** 그 질문 하나의 시도(답 본문 포함, 낸 시각 차례) — 다시 보기(명세 16 · 18.1) */
    fun attemptsOfQuestion(requestId: String, presentedToken: String?, topicPath: String, questionNumber: Int): VisitorRead<List<StoredAttempt>> =
        readFor(requestId, presentedToken, "list question attempts") { visitor -> records.attemptsOfQuestion(visitor.id, topicPath, questionNumber) }

    /** 이 방문자의 그 시도(없거나 남의 것이면 null) — 이미 저장된 시도의 재전송을 판 확인보다 먼저 알아보는 데 쓴다(명세 20) */
    fun ownAttempt(requestId: String, presentedToken: String?, attemptId: UUID): VisitorRead<StoredAttempt?> =
        readFor(requestId, presentedToken, "find attempt") { visitor -> records.findAttempt(visitor.id, attemptId) }

    /** 가장 최근 시도 [limit] 줄까지(본문 없이, 낸 시각 차례) — 복습 목록(명세 18.2) */
    fun recentAttempts(requestId: String, presentedToken: String?, limit: Int): VisitorRead<List<AttemptSummary>> =
        readFor(requestId, presentedToken, "list recent attempts") { visitor -> records.recentAttemptsOf(visitor.id, limit) }

    /** 스스로 고른 결과를 그 시도에 덧붙인다 — 모르는 방문자는 만들지 않는다 */
    fun grade(requestId: String, presentedToken: String?, attemptId: UUID, grade: Grade): GradeOutcome {
        val visitor = when (val lookup = identify(requestId, presentedToken)) {
            is VisitorLookup.Found -> lookup.visitor
            VisitorLookup.Unknown -> return GradeOutcome.VisitorUnknown
            VisitorLookup.Unavailable -> return GradeOutcome.StorageUnavailable
        }
        return try {
            val graded = records.recordGrade(visitor.id, attemptId, grade, clock.instant())
                ?: return GradeOutcome.AttemptNotFound
            requestLog.log(requestId, "progress graded topic=${graded.topicPath} question=${graded.questionNumber} grade=${graded.grade}")
            GradeOutcome.Graded(graded)
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "grade", error)
            GradeOutcome.StorageUnavailable
        }
    }

    /** 내 기록 지우기 전에 보일 수 — 모르는 방문자는 0 */
    fun countForDeletion(requestId: String, presentedToken: String?): CountOutcome {
        val visitor = when (val lookup = identify(requestId, presentedToken)) {
            is VisitorLookup.Found -> lookup.visitor
            VisitorLookup.Unknown -> return CountOutcome.Counted(0)
            VisitorLookup.Unavailable -> return CountOutcome.StorageUnavailable
        }
        return try {
            CountOutcome.Counted(records.countAttempts(visitor.id))
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "count", error)
            CountOutcome.StorageUnavailable
        }
    }

    /** 그 방문자의 시도 · 채점 · 방문자 줄을 지운다 — 모르는 방문자는 지울 것이 없다(0) */
    fun deleteMine(requestId: String, presentedToken: String?): DeleteOutcome {
        val visitor = when (val lookup = identify(requestId, presentedToken)) {
            is VisitorLookup.Found -> lookup.visitor
            VisitorLookup.Unknown -> return DeleteOutcome.Deleted(0)
            VisitorLookup.Unavailable -> return DeleteOutcome.StorageUnavailable
        }
        return try {
            val deleted = records.deleteVisitor(visitor.id)
            requestLog.log(requestId, "progress visitor deleted attempts=$deleted")
            DeleteOutcome.Deleted(deleted)
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "delete", error)
            DeleteOutcome.StorageUnavailable
        }
    }

    /**
     * 마지막 방문이 한국 날짜로 [RETENTION] 전보다 앞선 방문자를 지운다 — 한 묶음은 [purgeBatchSize] 명(한 문장이 시간 제한에
     * 걸리지 않게), 남으면 되풀이하고 한 실행에서 [purgeMaxPerRun] 명에 닿으면 멈춰 남은 수를 결과와 로그에 남긴다(명세 15).
     * 어떤 실패도 던지지 않고 로그와 결과로 남긴다 — 앞 묶음에서 지운 것은 그대로 지워진 채다.
     */
    fun purgeInactiveVisitors(requestId: String): PurgeOutcome {
        val cutoff = today().minus(RETENTION)
        var purged = 0
        return try {
            while (purged < purgeMaxPerRun) {
                val limit = minOf(purgeBatchSize, purgeMaxPerRun - purged)
                val deleted = records.deleteVisitorsLastVisitedBefore(cutoff, limit)
                purged += deleted
                if (deleted < limit) break
            }
            val remaining = if (purged >= purgeMaxPerRun) records.countVisitorsLastVisitedBefore(cutoff) else 0
            val level = if (remaining > 0) "warning" else "info"
            requestLog.log(requestId, "progress retention purged visitors=$purged remaining=$remaining lastVisitBefore=$cutoff", level)
            PurgeOutcome.Purged(purged, remaining)
        } catch (error: Exception) {
            requestLog.log(requestId, "progress retention failed: ${error.javaClass.simpleName} purgedBeforeFailure=$purged lastVisitBefore=$cutoff", "error")
            PurgeOutcome.Failed
        }
    }
}
