package xyz.junproject.backend.progress.usecase

import xyz.junproject.backend.progress.domain.Grade
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Visitor(val id: Long, val lastVisitOn: LocalDate)

/** 브라우저가 낸 답 — attemptId 는 브라우저가 시도마다 만든 고유 번호(같은 번호의 제출은 한 줄) */
data class AnswerSubmission(
    val attemptId: UUID,
    val topicPath: String,
    val questionNumber: Int,
    val answer: String,
    val contentSha: String,
    val questionHash: String,
)

/** 저장된 시도 + 그 채점(없으면 null) */
data class StoredAttempt(
    val attemptId: UUID,
    val topicPath: String,
    val questionNumber: Int,
    val answer: String,
    val contentSha: String,
    val questionHash: String,
    val submittedAt: Instant,
    val grade: Grade?,
    val gradedAt: Instant?,
)

/**
 * 상태 계산용 시도 한 줄 — 쓴 답의 본문은 싣지 않는다(명세 16). 본문은 질문 하나 단위로만 읽는다(03b).
 */
data class AttemptSummary(
    val attemptId: UUID,
    val topicPath: String,
    val questionNumber: Int,
    val contentSha: String,
    val questionHash: String,
    val submittedAt: Instant,
    val grade: Grade?,
    val gradedAt: Instant?,
) {
    /** 복습 단계 계산([xyz.junproject.backend.progress.domain.ReviewSchedule.progressOf])에 넘기는 꼴 */
    fun reviewed(): xyz.junproject.backend.progress.domain.ReviewedAttempt = TODO("task 02 리뷰 루프 2")
}

/**
 * 방문자 · 시도 · 채점의 저장소. DB 오류는 예외(스프링의 DataAccessException)로 던진다 — "없음"과 "확인 불가"를 가른다.
 * 시도와 채점은 덧붙이기만 한다(어느 줄도 고치지 않는다).
 */
interface ProgressRecords {
    /** 표 만들기 — 여러 번 · 두 자리가 동시에 돌아도 안전하다 */
    fun createSchema()
    fun findVisitor(tokenHash: String): Visitor?
    fun createVisitor(tokenHash: String, today: LocalDate, now: Instant): Visitor
    fun touchVisit(visitorId: Long, today: LocalDate)
    /** 같은 attemptId 가 이미 이 방문자의 것이면 그 줄을 그대로 돌려준다. 다른 방문자의 것이면 null */
    fun recordAttempt(visitorId: Long, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt?
    /** 이 방문자의 그 시도(채점 포함) — 없거나 남의 것이면 null. 하루 상한에 닿은 뒤의 재전송을 알아보는 데 쓴다 */
    fun findAttempt(visitorId: Long, attemptId: UUID): StoredAttempt?
    /** 이 방문자의 시도가 아니면 null. 이미 채점했으면 첫 채점을 그대로 둔 채 돌려준다 */
    fun recordGrade(visitorId: Long, attemptId: UUID, grade: Grade, gradedAt: Instant): StoredAttempt?
    /** 낸 시각 오름차순 — 답 본문 없이(본문 칸을 읽지 않는다, 명세 16) */
    fun attemptsOf(visitorId: Long): List<AttemptSummary>
    /** [from, until) 사이에 낸 시도의 수 */
    fun countAttemptsBetween(visitorId: Long, from: Instant, until: Instant): Int
    fun countAttempts(visitorId: Long): Int
    /** 방문자 줄과 그 시도 · 채점을 지운다 — 지운 시도의 수 */
    fun deleteVisitor(visitorId: Long): Int
    /**
     * 첫 답(쿠키 없음 · 모르는 토큰) — 방문자 줄과 시도를 한 트랜잭션으로 넣는다(명세 15). 시도 번호가 이미 다른 방문자의 것이면
     * 아무것도 남기지 않고 null. DB 오류면 아무것도 남기지 않고 DataAccessException.
     */
    fun recordFirstAttempt(tokenHash: String, today: LocalDate, submission: AnswerSubmission, submittedAt: Instant): StoredAttempt?
    /** 마지막 방문이 cutoff 보다 앞선(cutoff 당일은 남는다) 방문자를 [limit] 명까지 그 기록과 함께 지운다 — 지운 방문자의 수(명세 15: 묶음) */
    fun deleteVisitorsLastVisitedBefore(cutoff: LocalDate, limit: Int): Int
    /** 마지막 방문이 cutoff 보다 앞선 방문자의 수 — 보관 삭제가 총량 상한에서 멈췄을 때 남은 수 */
    fun countVisitorsLastVisitedBefore(cutoff: LocalDate): Int
}
