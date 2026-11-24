package xyz.junproject.backend.progress.usecase

import xyz.junproject.backend.progress.domain.Grade
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

    fun identify(requestId: String, presentedToken: String?): VisitorLookup = TODO("task 02")
    fun submitAnswer(requestId: String, presentedToken: String?, submission: AnswerSubmission): SubmitOutcome = TODO("task 02")
    fun grade(requestId: String, presentedToken: String?, attemptId: UUID, grade: Grade): GradeOutcome = TODO("task 02")
    fun countForDeletion(requestId: String, presentedToken: String?): CountOutcome = TODO("task 02")
    fun deleteMine(requestId: String, presentedToken: String?): DeleteOutcome = TODO("task 02")
    fun purgeInactiveVisitors(requestId: String): PurgeOutcome = TODO("task 02")
}
