package xyz.junproject.backend.progress.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 스스로 매긴 결과 — 맞음 · 부분 · 틀림 */
enum class Grade { CORRECT, PARTIAL, WRONG }

/** 복습 단계 — 다음 복습까지의 날 수. 익힘은 복습 목록에서 빠진다 */
enum class ReviewStage(val intervalDays: Long?) { ONE_DAY(1), THREE_DAYS(3), SEVEN_DAYS(7), MASTERED(null) }

/** 상태 계산에 드는 시도 하나 — grade 가 null 이면 채점하지 않고 떠난 시도 */
data class ReviewedAttempt(val submittedAt: Instant, val grade: Grade?)

/**
 * 질문 하나의 지금 상태 — 저장하지 않고 시도에서 계산한다(명세 2.2).
 * stage 가 null 이면 아직 안 푼 질문. awaitingGrade 는 마지막 시도를 채점하지 않았다는 뜻.
 */
data class QuestionProgress(val stage: ReviewStage?, val nextReviewOn: LocalDate?, val awaitingGrade: Boolean) {
    /** 그날 복습할 것인가 — 안 푼 질문 · 익힌 질문은 아니다 */
    fun isDueOn(today: LocalDate): Boolean = TODO("task 02")
}

/** 복습 간격 1 · 3 · 7일(명세 2.3 · 11.2) — 날짜는 한국 시각 */
object ReviewSchedule {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun dateOf(instant: Instant): LocalDate = TODO("task 02")

    fun progressOf(attempts: List<ReviewedAttempt>): QuestionProgress = TODO("task 02")
}
