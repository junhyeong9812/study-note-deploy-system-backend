package xyz.junproject.backend.progress.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade.CORRECT
import xyz.junproject.backend.progress.domain.Grade.PARTIAL
import xyz.junproject.backend.progress.domain.Grade.WRONG
import xyz.junproject.backend.progress.domain.ReviewStage.MASTERED
import xyz.junproject.backend.progress.domain.ReviewStage.ONE_DAY
import xyz.junproject.backend.progress.domain.ReviewStage.SEVEN_DAYS
import xyz.junproject.backend.progress.domain.ReviewStage.THREE_DAYS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 명세 2.3 · 11.2 — 복습 간격 1 → 3 → 7일, 7일 단계에서 맞히면 익힘. 틀림 · 부분 · 채점 안 함은 처음(1일)으로.
 * 복습일 전에 맞혀도 단계는 오르지 않는다. 익힘 뒤 틀리면 처음으로. 날짜는 한국 시각.
 */
class ReviewScheduleTest {
    private val korea = ZoneId.of("Asia/Seoul")

    /** 한국 시각 2026-11-[day] [hour]시 — 12월은 day 에 30 을 넘겨 쓴다(예: 36 = 12-06) */
    private fun at(day: Int, hour: Int = 10): Instant =
        LocalDate.of(2026, 11, 1).plusDays(day - 1L).atTime(hour, 0).atZone(korea).toInstant()

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 11, 1).plusDays(day - 1L)

    private fun attempt(day: Int, grade: Grade?, hour: Int = 10) = ReviewedAttempt(at(day, hour), grade)

    private fun progress(vararg attempts: ReviewedAttempt) = ReviewSchedule.progressOf(attempts.toList())

    /** 11-25 첫 정답 → 11-26 → 11-29 정답으로 7일 단계(다음 복습 12-06) */
    private val toSevenDays = arrayOf(attempt(25, CORRECT), attempt(26, CORRECT), attempt(29, CORRECT))

    @Test
    fun `안 푼 질문은 단계도 복습일도 없고 복습할 것이 아니다`() {
        val progress = progress()
        assertEquals(QuestionProgress(null, null, false), progress)
        assertFalse(progress.isDueOn(date(25)))
    }

    @Test
    fun `처음 맞히면 1일 단계이고 다음 복습일은 다음 날이다`() {
        assertEquals(QuestionProgress(ONE_DAY, date(26), false), progress(attempt(25, CORRECT)))
    }

    @Test
    fun `복습일마다 맞히면 1일 3일 7일 뒤로 늘고 7일 단계에서 맞히면 익힘이다`() {
        assertEquals(QuestionProgress(THREE_DAYS, date(29), false), progress(attempt(25, CORRECT), attempt(26, CORRECT)))
        assertEquals(QuestionProgress(SEVEN_DAYS, date(36), false), progress(*toSevenDays))
        val mastered = progress(*toSevenDays, attempt(36, CORRECT))
        assertEquals(QuestionProgress(MASTERED, null, false), mastered)
        assertFalse(mastered.isDueOn(date(36)))
        assertFalse(mastered.isDueOn(date(400)))
    }

    @Test
    fun `틀리면 어느 단계에서든 처음으로 돌아가고 다음 날 복습한다`() {
        // 7일 단계의 복습일(12-06) 전인 12-01 에 틀려도 처음으로
        assertEquals(QuestionProgress(ONE_DAY, date(32), false), progress(*toSevenDays, attempt(31, WRONG)))
        assertEquals(QuestionProgress(ONE_DAY, date(26), false), progress(attempt(25, WRONG)))
    }

    @Test
    fun `부분이면 틀린 것과 같게 처음으로 돌아간다`() {
        assertEquals(QuestionProgress(ONE_DAY, date(32), false), progress(*toSevenDays, attempt(31, PARTIAL)))
    }

    @Test
    fun `처음으로 돌아간 뒤에는 다시 1일 3일 7일을 밟는다`() {
        val afterReset = arrayOf(*toSevenDays, attempt(31, WRONG))       // 1일 단계, 복습 12-02
        assertEquals(QuestionProgress(THREE_DAYS, date(35), false), progress(*afterReset, attempt(32, CORRECT)))
    }

    @Test
    fun `채점하지 않고 떠난 시도는 채점 안 함이고 단계는 처음으로 둔다`() {
        val progress = progress(attempt(25, CORRECT), attempt(26, CORRECT), attempt(29, null))
        assertEquals(QuestionProgress(ONE_DAY, date(30), true), progress)
    }

    @Test
    fun `채점 안 한 시도 뒤에 채점한 시도가 오면 채점 안 함이 풀린다`() {
        val progress = progress(attempt(25, null), attempt(26, CORRECT))
        assertFalse(progress.awaitingGrade)
        assertEquals(THREE_DAYS, progress.stage)
    }

    @Test
    fun `복습일 전에 맞혀도 단계는 오르지 않는다`() {
        // 1일 단계 — 같은 날 다시 맞힘
        assertEquals(QuestionProgress(ONE_DAY, date(26), false), progress(attempt(25, CORRECT, hour = 9), attempt(25, CORRECT, hour = 22)))
        // 3일 단계(복습 11-29) — 하루 앞선 11-28 에 맞힘
        assertEquals(QuestionProgress(THREE_DAYS, date(29), false), progress(attempt(25, CORRECT), attempt(26, CORRECT), attempt(28, CORRECT)))
    }

    @Test
    fun `복습일이 지난 뒤 맞히면 한 단계 오르고 다음 복습일은 맞힌 날부터 센다`() {
        assertEquals(QuestionProgress(THREE_DAYS, date(31), false), progress(attempt(25, CORRECT), attempt(28, CORRECT)))
    }

    @Test
    fun `익힘 뒤에 틀리면 처음으로 돌아가고 맞히면 익힘 그대로다`() {
        val mastered = arrayOf(*toSevenDays, attempt(36, CORRECT))
        assertEquals(QuestionProgress(ONE_DAY, date(51), false), progress(*mastered, attempt(50, WRONG)))
        assertEquals(QuestionProgress(MASTERED, null, false), progress(*mastered, attempt(50, CORRECT)))
    }

    @Test
    fun `시도의 차례는 넘겨받은 순서가 아니라 낸 시각으로 본다`() {
        assertEquals(QuestionProgress(THREE_DAYS, date(29), false), progress(attempt(26, CORRECT), attempt(25, CORRECT)))
    }

    @Test
    fun `날짜는 한국 시각으로 센다 - UTC 로는 전날인 자정 넘은 시각`() {
        assertEquals(LocalDate.of(2026, 11, 25), ReviewSchedule.dateOf(Instant.parse("2026-11-24T15:00:00Z")))
        assertEquals(LocalDate.of(2026, 11, 24), ReviewSchedule.dateOf(Instant.parse("2026-11-24T14:59:59Z")))

        val first = ReviewedAttempt(Instant.parse("2026-11-25T14:30:00Z"), CORRECT)    // 한국 11-25 23:30 → 복습 11-26
        assertEquals(QuestionProgress(ONE_DAY, LocalDate.of(2026, 11, 26), false), ReviewSchedule.progressOf(listOf(first)))
        // 한국 11-26 00:10(UTC 로는 아직 11-25) — 복습일이므로 오른다
        val onDue = ReviewedAttempt(Instant.parse("2026-11-25T15:10:00Z"), CORRECT)
        assertEquals(QuestionProgress(THREE_DAYS, LocalDate.of(2026, 11, 29), false), ReviewSchedule.progressOf(listOf(first, onDue)))
    }

    @Test
    fun `복습일이 된 날부터 복습할 것이다`() {
        val progress = progress(attempt(25, CORRECT))    // 복습 11-26
        assertFalse(progress.isDueOn(date(25)))
        assertTrue(progress.isDueOn(date(26)))
        assertTrue(progress.isDueOn(date(27)))
    }
}
