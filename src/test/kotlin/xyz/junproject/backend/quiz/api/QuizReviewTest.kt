package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.OTHER_TOPIC
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.usecase.QuizService
import java.time.Instant

/**
 * 명세 18.1 · 18.2 · 2.3 · 11.2 — 오늘 복습할 것 `GET /api/quiz/review`.
 * 복습일이 된 질문만(한국 시각의 오늘) · 옛 질문의 기록은 계산에서 빠진다 · 주제 · 번호 · 묶음 · 질문 글의 앞부분(정답 없음) ·
 * 쿠키가 없으면 빈 목록 · 읽는 줄 수의 상한(최근 것부터, 잘렸다는 표시) · 쪼갤 수 없게 된 주제 · 없어진 주제는 수로 드러난다.
 */
class QuizReviewTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(data: Map<String, Any?>) = data["items"] as List<Map<String, Any?>>

    private fun textOf(topic: String, number: Int) = when (topic) {
        TOPIC -> QuizContent.processSplit
        else -> QuizContent.tcpSplit
    }.questions.single { it.number == number }.text

    private fun item(topic: String, number: Int, group: String?, stage: String, nextReviewOn: String) = mapOf(
        "topic" to topic, "number" to number, "group" to group,
        "preview" to textOf(topic, number).take(QuizService.REVIEW_PREVIEW_CHARS), "stage" to stage, "next_review_on" to nextReviewOn,
    )

    private fun at(text: String): Instant = Instant.parse(text)

    @Test
    fun `복습일이 된 질문만 다음 복습일 차례로 나오고 정답 글이 없다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T01:00:00Z"), Grade.CORRECT)      // 1일 단계 → 11-25 오늘
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW, Grade.WRONG)                    // 11-26 → 아직
        bed.putAttempt(visitor, TOPIC, 3, at("2026-11-23T01:00:00Z"), Grade.WRONG)        // 11-24 → 지남
        bed.putAttempt(visitor, OTHER_TOPIC, 1, at("2026-11-20T01:00:00Z"), Grade.WRONG)  // 11-21 → 지남
        listOf("2026-11-01", "2026-11-02", "2026-11-05", "2026-11-12").forEach { day ->     // 익힘 → 목록에서 빠진다
            bed.putAttempt(visitor, OTHER_TOPIC, 2, at("${day}T01:00:00Z"), Grade.CORRECT)
        }

        val response = bed.review(bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals("loaded", data["progress"])
        assertEquals(listOf(
            item(OTHER_TOPIC, 1, null, "ONE_DAY", "2026-11-21"),
            item(TOPIC, 3, "B. 응용", "ONE_DAY", "2026-11-24"),
            item(TOPIC, 1, "A. 개념", "ONE_DAY", "2026-11-25"),
        ), itemsOf(data))
        assertEquals(false, data["truncated"])
        assertEquals(0, data["missing_topics"])
        assertEquals(0, data["unsplittable_topics"])
        assertEquals(0, data["missing_questions"])
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(response.contentAsString.contains(marker), response.contentAsString) }
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"), "읽기 경로는 쿠키를 놓지 않는다")
    }

    @Test
    fun `질문 글의 앞부분은 정한 길이에서 자른다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 3, at("2026-11-23T01:00:00Z"), Grade.WRONG)
        val preview = itemsOf(bed.data(bed.review(bed.visitorCookie(token)))).single()["preview"] as String
        assertTrue(textOf(TOPIC, 3).length > QuizService.REVIEW_PREVIEW_CHARS)
        assertEquals(QuizService.REVIEW_PREVIEW_CHARS, preview.length)
        assertTrue(textOf(TOPIC, 3).startsWith(preview))
    }

    @Test
    fun `오늘은 한국 시각으로 센다`() {
        val (token, visitor) = bed.knownVisitor()
        // 한국 11-25 00:30 (UTC 로는 11-24) 에 틀림 → 다음 복습은 한국 11-26
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T15:30:00Z"), Grade.WRONG)
        bed.clock.instant = at("2026-11-25T14:59:00Z")   // 한국 11-25 23:59 — 아직
        assertEquals(emptyList<Any>(), itemsOf(bed.data(bed.review(bed.visitorCookie(token)))))
        bed.clock.instant = at("2026-11-25T15:00:00Z")   // 한국 11-26 00:00 — 됨
        assertEquals(listOf(TOPIC to 1), itemsOf(bed.data(bed.review(bed.visitorCookie(token)))).map { it["topic"] to it["number"] })
    }

    @Test
    fun `옛 질문의 기록은 복습 계산에서 빠진다`() {
        val (token, visitor) = bed.knownVisitor()
        // 질문 1: 옛 질문에 11-23 맞힘 + 지금 질문에 11-24 맞힘 — 옛 기록을 넣으면 11-24 에 3일 단계로 올라 오늘은 아니다.
        // 빼면 지금 질문의 첫 시도라 1일 단계 → 11-25 오늘이다.
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-23T01:00:00Z"), Grade.CORRECT, questionHash = QuizContent.UNKNOWN_HASH)
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T01:00:00Z"), Grade.CORRECT)
        // 질문 2: 옛 질문(옛 판의 해시)에 틀린 기록만 — 넣으면 복습할 것이 된다
        bed.putAttempt(visitor, TOPIC, 2, at("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.oldQuestionTwoHash,
            contentSha = QuizContent.OLD_SHA)

        val items = itemsOf(bed.data(bed.review(bed.visitorCookie(token))))

        assertEquals(listOf(item(TOPIC, 1, "A. 개념", "ONE_DAY", "2026-11-25")), items)
    }

    @Test
    fun `쿠키가 없으면 빈 목록이고 저장소에 닿지 않으며 쿠키를 놓지 않는다`() {
        val response = bed.review()
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals("none", data["progress"])
        assertEquals(emptyList<Any>(), data["items"])
        assertEquals(false, data["truncated"])
        assertEquals(0, data["missing_topics"])
        assertEquals(0, data["unsplittable_topics"])
        assertEquals(0, data["missing_questions"])
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `읽는 줄 수에 상한이 있고 넘으면 최근 것부터 읽고 잘렸다고 표시한다`() {
        QuizTestBed(reviewAttemptLimit = 3).use { capped ->
            val (token, visitor) = capped.knownVisitor()
            capped.putAttempt(visitor, OTHER_TOPIC, 1, at("2026-11-20T01:00:00Z"), Grade.WRONG)   // 가장 오래됨 — 복습할 것
            listOf(3L, 2L, 1L).forEach { minutesAgo ->
                capped.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW.minusSeconds(60 * minutesAgo), Grade.WRONG)
            }

            val data = capped.data(capped.review(capped.visitorCookie(token)))

            assertEquals(true, data["truncated"])
            assertEquals(emptyList<Any>(), data["items"], "잘린 오래된 시도가 계산에 들어갔다")
            assertTrue(capped.fake.recentReadLimits.isNotEmpty(), capped.fake.calls.toString())
            assertTrue(capped.fake.recentReadLimits.all { it <= 4 }, "상한보다 많이 읽었다: ${capped.fake.recentReadLimits}")
            assertFalse("attemptsOf" in capped.fake.calls, "방문자의 모든 시도를 읽었다")
        }
    }

    @Test
    fun `읽은 줄 수가 상한과 같으면 잘리지 않았다`() {
        QuizTestBed(reviewAttemptLimit = 3).use { capped ->
            val (token, visitor) = capped.knownVisitor()
            capped.putAttempt(visitor, OTHER_TOPIC, 1, at("2026-11-20T01:00:00Z"), Grade.WRONG)
            capped.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW.minusSeconds(120), Grade.WRONG)
            capped.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW.minusSeconds(60), Grade.WRONG)

            val data = capped.data(capped.review(capped.visitorCookie(token)))

            assertEquals(false, data["truncated"])
            assertEquals(listOf(OTHER_TOPIC to 1), itemsOf(data).map { it["topic"] to it["number"] })
        }
    }

    @Test
    fun `기본 상한은 5000 줄이다`() {
        assertEquals(5_000, QuizService.REVIEW_ATTEMPT_LIMIT)
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T01:00:00Z"), Grade.CORRECT)
        bed.review(bed.visitorCookie(token))
        assertTrue(bed.fake.recentReadLimits.isNotEmpty() && bed.fake.recentReadLimits.all { it <= 5_001 }, bed.fake.recentReadLimits.toString())
    }

    @Test
    fun `지금 쪼갤 수 없게 된 주제와 없어진 주제의 기록은 조용히 빠지지 않고 주제의 수로 드러난다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, QuizContent.BROKEN_TOPIC, 1, at("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)
        bed.putAttempt(visitor, QuizContent.GONE_TOPIC, 1, at("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)
        bed.putAttempt(visitor, QuizContent.GONE_TOPIC, 2, at("2026-11-21T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T01:00:00Z"), Grade.CORRECT)

        val response = bed.review(bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(listOf(TOPIC to 1), itemsOf(data).map { it["topic"] to it["number"] })
        assertEquals(1, data["missing_topics"])
        assertEquals(1, data["unsplittable_topics"])
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(response.contentAsString.contains(marker), response.contentAsString) }
    }

    @Test
    fun `주제는 있는데 질문 번호가 없어진 기록은 조용히 빠지지 않고 질문의 수로 드러난다`() {
        val (token, visitor) = bed.knownVisitor()
        // 지금 판의 질문은 1~3 — 7 과 9 는 없어졌다. 7 은 시도가 둘이어도 질문 하나로 센다
        bed.putAttempt(visitor, TOPIC, 7, at("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)
        bed.putAttempt(visitor, TOPIC, 7, at("2026-11-21T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)
        // 9 는 지금 1번의 글과 같은 해시 — 해시만으로 짝지으면 1번에 섞인다
        bed.putAttempt(visitor, TOPIC, 9, at("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.hashOf(TOPIC, 1))
        bed.putAttempt(visitor, OTHER_TOPIC, 5, at("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T01:00:00Z"), Grade.CORRECT)

        val response = bed.review(bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(listOf(item(TOPIC, 1, "A. 개념", "ONE_DAY", "2026-11-25")), itemsOf(data))
        assertEquals(3, data["missing_questions"])
        assertEquals(0, data["missing_topics"])
        assertEquals(0, data["unsplittable_topics"])
    }

    @Test
    fun `저장소가 죽으면 기록을 불러올 수 없음으로 답하고 쿠키를 건드리지 않는다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, at("2026-11-24T01:00:00Z"), Grade.CORRECT)
        listOf("findVisitor", "recentAttemptsOf").forEach { failing ->
            bed.fake.failing.clear()
            bed.fake.failing += failing
            val response = bed.review(bed.visitorCookie(token))
            assertEquals(200, response.status, "$failing ${response.contentAsString}")
            val data = bed.data(response)
            assertEquals("unavailable", data["progress"], failing)
            assertEquals(emptyList<Any>(), data["items"])
            assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        }
    }
}
