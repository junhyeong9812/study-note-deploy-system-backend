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
    /** issuedToken: 이번에 새로 발급한 토큰(쿠키로 내보낼 원문) — 아는 방문자면 null */
    data class Saved(val attempt: StoredAttempt, val issuedToken: String?) : SubmitOutcome
    data object AnswerTooLong : SubmitOutcome
    data object DailyLimitReached : SubmitOutcome
    /** 같은 시도 번호를 다른 방문자가 이미 씀 */
    data object AttemptIdTaken : SubmitOutcome
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

sealed interface PurgeOutcome {
    data class Purged(val visitors: Int) : PurgeOutcome
    data object Failed : PurgeOutcome
}

/** 방문자 식별과 공부 기록(명세 2.1 · 2.2 · 11.2). 시각은 주입한 시계, 날짜는 한국 시각 */
class StudyProgressService(
    private val records: ProgressRecords,
    private val clock: Clock,
    private val requestLog: RequestLog,
    private val issueToken: () -> String = { VisitorToken.issue() },
) {
    companion object {
        const val ANSWER_MAX_CHARS = 4_000
        const val DAILY_ATTEMPT_LIMIT = 1_000
        val RETENTION: Period = Period.ofYears(1)
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
     * 답을 덧붙인다. 길이 검증이 먼저(방문자 · 토큰을 만들기 전), 모르는 방문자면 여기서 처음 토큰을 발급한다.
     * 확인 불가(DB 오류)면 발급하지 않는다. 로그에는 주제 · 질문 번호 · 길이 · 결과만 — 답의 본문 · 토큰은 쓰지 않는다.
     */
    fun submitAnswer(requestId: String, presentedToken: String?, submission: AnswerSubmission): SubmitOutcome {
        val subject = "topic=${submission.topicPath} question=${submission.questionNumber}"
        val length = submission.answer.codePointCount(0, submission.answer.length)
        if (length > ANSWER_MAX_CHARS) {
            requestLog.log(requestId, "progress answer rejected(too long) $subject length=$length", "warning")
            return SubmitOutcome.AnswerTooLong
        }
        val now = clock.instant()
        val today = ReviewSchedule.dateOf(now)
        try {
            var issuedToken: String? = null
            val visitor = when (val lookup = identify(requestId, presentedToken)) {
                is VisitorLookup.Found -> lookup.visitor
                VisitorLookup.Unavailable -> return SubmitOutcome.StorageUnavailable
                VisitorLookup.Unknown -> {
                    val token = issueToken()
                    records.createVisitor(VisitorToken.hash(token), today, now).also { issuedToken = token }
                }
            }
            // 단순화: 세고 나서 넣는다(원자적이 아니다) — 한 방문자의 요청이 동시에 몰리면 상한을 그 동시 수만큼 넘길 수 있다(1,000 에 몇 건).
            // 정확히 막아야 하면 방문자 줄을 잠그고(SELECT ... FOR UPDATE) 세기와 넣기를 한 트랜잭션으로 묶는다.
            if (issuedToken == null) {
                val dayStart = today.atStartOfDay(ReviewSchedule.ZONE).toInstant()
                val dayEnd = today.plusDays(1).atStartOfDay(ReviewSchedule.ZONE).toInstant()
                if (records.countAttemptsBetween(visitor.id, dayStart, dayEnd) >= DAILY_ATTEMPT_LIMIT) {
                    // 상한에 닿은 뒤에도 이미 저장한 시도의 재전송(두 번 눌림)은 처음 결과를 돌려준다
                    records.findAttempt(visitor.id, submission.attemptId)?.let { return SubmitOutcome.Saved(it, null) }
                    requestLog.log(requestId, "progress answer rejected(daily limit) $subject length=$length", "warning")
                    return SubmitOutcome.DailyLimitReached
                }
            }
            val stored = records.recordAttempt(visitor.id, submission, now)
            if (stored == null) {
                requestLog.log(requestId, "progress answer rejected(attempt id taken) $subject", "warning")
                return SubmitOutcome.AttemptIdTaken
            }
            requestLog.log(requestId, "progress answer saved $subject length=$length newVisitor=${issuedToken != null}")
            return SubmitOutcome.Saved(stored, issuedToken)
        } catch (error: DataAccessException) {
            logStorageFailure(requestId, "submit answer($subject)", error)
            return SubmitOutcome.StorageUnavailable
        }
    }

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

    /** 마지막 방문이 한국 날짜로 [RETENTION] 전보다 앞선 방문자를 지운다. 어떤 실패도 던지지 않고 로그와 결과로 남긴다 */
    fun purgeInactiveVisitors(requestId: String): PurgeOutcome {
        val cutoff = today().minus(RETENTION)
        return try {
            val purged = records.deleteVisitorsLastVisitedBefore(cutoff)
            requestLog.log(requestId, "progress retention purged visitors=$purged lastVisitBefore=$cutoff")
            PurgeOutcome.Purged(purged)
        } catch (error: Exception) {
            requestLog.log(requestId, "progress retention failed: ${error.javaClass.simpleName} lastVisitBefore=$cutoff", "error")
            PurgeOutcome.Failed
        }
    }
}
