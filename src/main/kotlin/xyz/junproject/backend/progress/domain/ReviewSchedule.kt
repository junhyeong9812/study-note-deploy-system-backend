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
    fun isDueOn(today: LocalDate): Boolean = nextReviewOn != null && !today.isBefore(nextReviewOn)
}

/** 복습 간격 1 · 3 · 7일(명세 2.3 · 11.2) — 날짜는 한국 시각 */
object ReviewSchedule {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun dateOf(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    /**
     * 낸 시각 차례로 시도를 밟는다. 틀림 · 부분 · 채점 안 함은 1일 단계로 돌리고 다음 복습은 그 다음 날.
     * 맞힘은 첫 시도면 1일 단계, 복습일이 되었으면(늦어도) 한 단계 오르고 다음 복습일은 맞힌 날부터 센다,
     * 복습일 전이면 그대로(기록만 남는다), 익힘이면 익힘 그대로.
     */
    fun progressOf(attempts: List<ReviewedAttempt>): QuestionProgress =
        attempts.sortedBy { it.submittedAt }.fold(QuestionProgress(null, null, false)) { current, attempt ->
            val day = dateOf(attempt.submittedAt)
            when (attempt.grade) {
                null -> restart(day).copy(awaitingGrade = true)
                Grade.WRONG, Grade.PARTIAL -> restart(day)
                Grade.CORRECT -> when {
                    current.stage == null -> restart(day)
                    current.stage == ReviewStage.MASTERED -> current.copy(awaitingGrade = false)
                    current.isDueOn(day) -> advance(current.stage, day)
                    else -> current.copy(awaitingGrade = false)
                }
            }
        }

    private fun restart(day: LocalDate) = QuestionProgress(ReviewStage.ONE_DAY, day.plusDays(1), false)

    private fun advance(stage: ReviewStage, day: LocalDate): QuestionProgress {
        val next = when (stage) {
            ReviewStage.ONE_DAY -> ReviewStage.THREE_DAYS
            ReviewStage.THREE_DAYS -> ReviewStage.SEVEN_DAYS
            ReviewStage.SEVEN_DAYS, ReviewStage.MASTERED -> ReviewStage.MASTERED
        }
        return QuestionProgress(next, next.intervalDays?.let(day::plusDays), false)
    }
}
