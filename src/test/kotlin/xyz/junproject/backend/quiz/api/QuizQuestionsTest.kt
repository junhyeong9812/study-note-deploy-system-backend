package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import java.time.Instant

/**
 * 명세 18.1 · 18.2 — 주제의 질문 목록 `GET /api/quiz/questions?topic=`.
 * 정답은 싣지 않는다 · 쿠키가 있으면 질문별 상태(주제 단위로 읽은 시도로 계산) · 쿠키가 없으면 상태 없이 · 쪼갤 수 없으면 "퀴즈 없음" ·
 * 경로 검증 · DB 가 죽어도 질문은 나온다.
 */
class QuizQuestionsTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    @Suppress("UNCHECKED_CAST")
    private fun questionsOf(data: Map<String, Any?>) = data["questions"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun statusOf(question: Map<String, Any?>) = question["status"] as Map<String, Any?>?

    private fun assertNoAnswerText(text: String) =
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(text.contains(marker), "정답 자료($marker)가 응답에 있다: $text") }

    @Test
    fun `정답이 질문 목록 응답 어디에도 없다 - 쿠키가 없을 때와 기록이 있을 때 모두`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)

        val anonymous = bed.questions(TOPIC)
        val withRecords = bed.questions(TOPIC, bed.visitorCookie(token))

        assertEquals(200, anonymous.status, anonymous.contentAsString)
        assertEquals(200, withRecords.status, withRecords.contentAsString)
        assertNoAnswerText(anonymous.contentAsString)
        assertNoAnswerText(withRecords.contentAsString)
    }

    @Test
    fun `질문마다 번호 묶음 글 해시가 파일의 차례대로 나오고 내용의 SHA 가 함께 나온다`() {
        val response = bed.questions(TOPIC)
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, bed.body(response)["success"])
        val data = bed.data(response)
        assertEquals(TOPIC, data["topic"])
        assertEquals(QuizContent.HEAD, data["content_sha"])
        assertEquals(true, data["quiz_available"])
        val expected = QuizContent.processSplit.questions
        val questions = questionsOf(data)
        assertEquals(expected.map { it.number }, questions.map { it["number"] })
        assertEquals(listOf("A. 개념", "A. 개념", "B. 응용"), questions.map { it["group"] })
        assertEquals(expected.map { it.text }, questions.map { it["text"] })
        assertEquals(expected.map { it.hash }, questions.map { it["hash"] })
    }

    @Test
    fun `묶음 제목이 없는 주제의 질문은 묶음이 null 이다`() {
        val questions = questionsOf(bed.data(bed.questions(QuizContent.OTHER_TOPIC)))
        assertEquals(listOf(null, null), questions.map { it["group"] })
    }

    @Test
    fun `쿠키가 없으면 상태 없이 질문만 나오고 저장소에 닿지 않으며 쿠키를 놓지 않는다`() {
        val response = bed.questions(TOPIC)
        val data = bed.data(response)
        assertEquals("none", data["progress"])
        assertEquals(listOf(null, null, null), questionsOf(data).map { statusOf(it) })
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(0, bed.fake.visitorsByHash.size)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `모르는 토큰도 상태 없이 질문만 나오고 방문자를 만들지 않는다`() {
        val response = bed.questions(TOPIC, bed.visitorCookie("Z".repeat(43)))
        assertEquals(200, response.status)
        assertEquals("none", bed.data(response)["progress"])
        assertEquals(0, bed.fake.visitorsByHash.size)
        assertEquals(0, bed.issuedTokens.size)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `쿠키가 있으면 질문별로 안 풂 채점 안 함 복습 단계 다음 복습일 옛 질문의 기록 여부가 나온다`() {
        val (token, visitor) = bed.knownVisitor()
        // 1: 어제(한국 11-24) 맞힘 → 1일 단계, 다음 복습 11-25 = 오늘 → 복습할 것
        bed.putAttempt(visitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        // 2: 오늘 내고 채점 안 함 → 채점 안 함, 1일 단계, 다음 복습 11-26
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        // 3: 옛 질문(해시가 다름)의 기록만 → 지금 질문은 안 풂, 옛 기록 있음
        bed.putAttempt(visitor, TOPIC, 3, Instant.parse("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)

        val response = bed.questions(TOPIC, bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals("loaded", data["progress"])
        val statuses = questionsOf(data).associate { it["number"] to statusOf(it)!! }

        assertEquals(mapOf("answered" to true, "awaiting_grade" to false, "stage" to "ONE_DAY", "next_review_on" to "2026-11-25",
            "due" to true, "has_outdated_attempts" to false), statuses[1])
        assertEquals(mapOf("answered" to true, "awaiting_grade" to true, "stage" to "ONE_DAY", "next_review_on" to "2026-11-26",
            "due" to false, "has_outdated_attempts" to false), statuses[2])
        assertEquals(mapOf("answered" to false, "awaiting_grade" to false, "stage" to null, "next_review_on" to null,
            "due" to false, "has_outdated_attempts" to true), statuses[3])
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"), "읽기 경로는 쿠키를 놓지 않는다")
    }

    @Test
    fun `읽는 시도는 그 주제의 것만이다 - 방문자의 모든 시도를 읽지 않는다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        // 다른 주제의 같은 번호 — 섞이면 1번이 푼 것으로 보인다
        bed.putAttempt(visitor, QuizContent.OTHER_TOPIC, 1, QuizTestBed.NOW, Grade.CORRECT)

        val data = bed.data(bed.questions(TOPIC, bed.visitorCookie(token)))

        assertEquals(listOf(visitor.id to TOPIC), bed.fake.topicReads)
        assertFalse("attemptsOf" in bed.fake.calls, bed.fake.calls.toString())
        assertFalse("recentAttemptsOf" in bed.fake.calls, bed.fake.calls.toString())
        val statuses = questionsOf(data).associate { it["number"] to statusOf(it)!! }
        assertEquals(false, statuses[1]!!["answered"])
        assertEquals(true, statuses[2]!!["answered"])
    }

    @Test
    fun `없어진 질문 번호의 시도는 어느 질문의 상태에도 섞이지 않는다`() {
        val (token, visitor) = bed.knownVisitor()
        // 7 번은 지금 판에 없다 — 해시는 지금 1번의 글과 같게 둬서, 해시만으로 짝지으면 1번에 섞이게 한다
        bed.putAttempt(visitor, TOPIC, 7, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT, questionHash = QuizContent.hashOf(TOPIC, 1))
        bed.putAttempt(visitor, TOPIC, 8, QuizTestBed.NOW, questionHash = QuizContent.UNKNOWN_HASH)

        val data = bed.data(bed.questions(TOPIC, bed.visitorCookie(token)))

        assertEquals("loaded", data["progress"])
        assertEquals(listOf(1, 2, 3), questionsOf(data).map { it["number"] })
        val unanswered = mapOf("answered" to false, "awaiting_grade" to false, "stage" to null, "next_review_on" to null,
            "due" to false, "has_outdated_attempts" to false)
        questionsOf(data).forEach { question -> assertEquals(unanswered, statusOf(question), "질문 ${question["number"]}") }
    }

    @Test
    fun `쪼갤 수 없는 주제는 예외가 아니라 그 이유와 함께 퀴즈 없음으로 200 이다`() {
        val response = bed.questions(QuizContent.BROKEN_TOPIC)
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(false, data["quiz_available"])
        assertEquals("MISSING_ANSWER", data["unavailable_reason"])
        assertEquals(listOf(2), data["unavailable_numbers"])
        assertEquals(emptyList<Any>(), data["questions"])
        assertNoAnswerText(response.contentAsString)
    }

    @Test
    fun `쪼갤 수 있는 주제는 퀴즈 없음의 이유가 비어 있다`() {
        val data = bed.data(bed.questions(TOPIC))
        assertEquals(null, data["unavailable_reason"])
        assertEquals(emptyList<Any>(), data["unavailable_numbers"])
    }

    @Test
    fun `cs 밖의 주제와 경로를 벗어나는 값은 422 로 거절하고 파일 저장소 어디에도 닿지 않는다`() {
        val rejected = listOf(
            QuizContent.OUTSIDE_TOPIC,                 // 파일은 있지만 cs 밖
            "cs/../${QuizContent.OUTSIDE_TOPIC}",      // 경로 벗어남
            "cs/os/../../${QuizContent.OUTSIDE_TOPIC}",
            "/$TOPIC",                                 // 절대 경로
            "$TOPIC\nreq-forged:backend:forged",       // 줄바꿈
            "$TOPIC\r",
            "$TOPIC:x",                                // 콜론
            "cs\\os\\process",                         // 역슬래시
            "cs/os/./process",
            "cs//os/process",
            "",
        )
        rejected.forEach { topic ->
            val response = bed.questions(topic)
            assertEquals(422, response.status, "topic=$topic ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response), "topic=$topic")
        }
        assertEquals(emptyList<String>(), bed.notes.everyReadPath())
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `cs 아래의 없는 주제는 404 not_found 이다`() {
        val response = bed.questions("cs/os/nothing-here")
        assertEquals(404, response.status, response.contentAsString)
        assertEquals("not_found", bed.errorCode(response))
        assertEquals(false, bed.body(response)["success"])
    }

    @Test
    fun `DB 가 죽어 방문자를 확인할 수 없어도 질문은 나오고 기록을 불러올 수 없음으로 표시하며 쿠키를 건드리지 않는다`() {
        val (token, _) = bed.knownVisitor()
        bed.fake.failing += "findVisitor"
        val response = bed.questions(TOPIC, bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals("unavailable", data["progress"])
        assertEquals(listOf(1, 2, 3), questionsOf(data).map { it["number"] })
        assertEquals(listOf(null, null, null), questionsOf(data).map { statusOf(it) })
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        assertNoAnswerText(response.contentAsString)
    }

    @Test
    fun `DB 가 시도를 읽다 죽어도 질문은 나오고 기록을 불러올 수 없음으로 표시한다`() {
        val (token, _) = bed.knownVisitor()
        bed.fake.failing += "attemptsOfTopic"
        val response = bed.questions(TOPIC, bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals("unavailable", data["progress"])
        assertEquals(3, questionsOf(data).size)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }
}
