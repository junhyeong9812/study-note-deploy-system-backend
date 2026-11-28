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
 * 명세 18.1 · 18.2 · 2.3 — 답을 낸 질문의 정답 · 내 답 다시 보기 `GET /api/quiz/answers?topic=&number=`.
 * 그 질문에 이 방문자의 (지금 질문의) 시도가 있을 때만 정답을 낸다 · 옛 질문의 기록은 "질문이 바뀌기 전의 답"으로 갈라 낸다 ·
 * 남의 쿠키로는 남의 답이 안 보인다 · 쓴 답의 본문은 질문 하나 단위로만 읽는다.
 */
class QuizRevisitTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    @Suppress("UNCHECKED_CAST")
    private fun entries(data: Map<String, Any?>, key: String) = data[key] as List<Map<String, Any?>>

    private fun assertNoAnswerText(text: String) =
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(text.contains(marker), "정답 자료($marker)가 응답에 있다: $text") }

    @Test
    fun `답을 낸 질문은 그 질문의 정답과 내 답들이 낸 차례로 나온다`() {
        val (token, visitor) = bed.knownVisitor()
        val first = bed.putAttempt(visitor, TOPIC, 2, Instant.parse("2026-11-24T01:00:00Z"), Grade.WRONG, answer = "첫 번째 내 답")
        val second = bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW, answer = "두 번째 내 답")
        bed.putAttempt(visitor, TOPIC, 1, QuizTestBed.NOW, answer = "다른 질문의 내 답")

        val response = bed.revisit(TOPIC, "2", bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(TOPIC, data["topic"])
        assertEquals(2, data["number"])
        val expected = QuizContent.processSplit.answerOf(2)!!
        assertEquals(mapOf("number" to 2, "title" to expected.title, "text" to expected.text), data["correct_answer"])
        assertEquals(listOf(
            mapOf("attempt_id" to first.toString(), "answer" to "첫 번째 내 답", "submitted_at" to "2026-11-24T01:00:00Z",
                "grade" to "WRONG", "graded_at" to "2026-11-24T01:01:00Z"),
            mapOf("attempt_id" to second.toString(), "answer" to "두 번째 내 답", "submitted_at" to "2026-11-25T01:00:00Z",
                "grade" to null, "graded_at" to null),
        ), entries(data, "attempts"))
        assertEquals(emptyList<Any>(), data["outdated_attempts"])
        val text = response.contentAsString
        listOf("ANSWER-MARK-1", "ANSWER-MARK-3", "다른 질문의 내 답").forEach { other -> assertFalse(text.contains(other), "$other: $text") }
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"), "읽기 경로는 쿠키를 놓지 않는다")
    }

    @Test
    fun `쓴 답의 본문은 그 질문 하나 단위로 읽는다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        bed.revisit(TOPIC, "2", bed.visitorCookie(token))
        assertEquals(listOf(Triple(visitor.id, TOPIC, 2)), bed.fake.questionReads)
        assertFalse("attemptsOf" in bed.fake.calls, bed.fake.calls.toString())
    }

    @Test
    fun `답을 내지 않은 질문은 404 not_answered 이고 정답 글이 없다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        val response = bed.revisit(TOPIC, "1", bed.visitorCookie(token))
        assertEquals(404, response.status, response.contentAsString)
        assertEquals("not_answered", bed.errorCode(response))
        assertNoAnswerText(response.contentAsString)
    }

    @Test
    fun `쿠키가 없거나 모르는 토큰이면 404 not_answered 이고 정답 글이 없다`() {
        val (_, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        listOf(bed.revisit(TOPIC, "2"), bed.revisit(TOPIC, "2", bed.visitorCookie("Z".repeat(43)))).forEach { response ->
            assertEquals(404, response.status, response.contentAsString)
            assertEquals("not_answered", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
            assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        }
        assertEquals(1, bed.fake.visitorsByHash.size)
    }

    @Test
    fun `남의 쿠키로는 남의 답과 정답이 보이지 않는다`() {
        val (_, owner) = bed.knownVisitor()
        bed.putAttempt(owner, TOPIC, 2, QuizTestBed.NOW, answer = "주인의 비밀 답")
        val (otherToken, _) = bed.knownVisitor()
        val response = bed.revisit(TOPIC, "2", bed.visitorCookie(otherToken))
        assertEquals(404, response.status, response.contentAsString)
        assertFalse(response.contentAsString.contains("주인의 비밀 답"))
        assertNoAnswerText(response.contentAsString)
    }

    @Test
    fun `옛 질문의 기록은 질문이 바뀌기 전의 답으로 갈라 낸다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, Instant.parse("2026-11-20T01:00:00Z"), Grade.CORRECT,
            questionHash = QuizContent.oldQuestionTwoHash, answer = "옛 질문에 쓴 답", contentSha = QuizContent.OLD_SHA)
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW, answer = "지금 질문에 쓴 답")

        val data = bed.data(bed.revisit(TOPIC, "2", bed.visitorCookie(token)))

        assertEquals(listOf("지금 질문에 쓴 답"), entries(data, "attempts").map { it["answer"] })
        assertEquals(listOf("옛 질문에 쓴 답"), entries(data, "outdated_attempts").map { it["answer"] })
        assertEquals(2, (data["correct_answer"] as Map<*, *>)["number"])
    }

    @Test
    fun `옛 질문의 기록만 있으면 지금 질문의 정답은 내주지 않고 옛 답만 보인다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, Instant.parse("2026-11-20T01:00:00Z"), Grade.CORRECT,
            questionHash = QuizContent.oldQuestionTwoHash, answer = "옛 질문에 쓴 답", contentSha = QuizContent.OLD_SHA)

        val response = bed.revisit(TOPIC, "2", bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(null, data["correct_answer"])
        assertEquals(emptyList<Any>(), data["attempts"])
        assertEquals(listOf("옛 질문에 쓴 답"), entries(data, "outdated_attempts").map { it["answer"] })
        assertNoAnswerText(response.contentAsString)
    }

    @Test
    fun `저장소가 죽으면 503 storage_unavailable 이고 정답 글이 없으며 쿠키를 건드리지 않는다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        listOf("findVisitor", "attemptsOfQuestion").forEach { failing ->
            bed.fake.failing.clear()
            bed.fake.failing += failing
            val response = bed.revisit(TOPIC, "2", bed.visitorCookie(token))
            assertEquals(503, response.status, "$failing ${response.contentAsString}")
            assertEquals("storage_unavailable", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
            assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        }
    }

    @Test
    fun `잘못된 주제 경로와 번호는 422 이고 없는 주제는 404 이며 저장소에 닿지 않는다`() {
        val (token, _) = bed.knownVisitor()
        listOf("cs/../x" to "2", QuizContent.OUTSIDE_TOPIC to "1", "$TOPIC\nx" to "2", TOPIC to "abc", TOPIC to "99").forEach { (topic, number) ->
            val response = bed.revisit(topic, number, bed.visitorCookie(token))
            assertEquals(422, response.status, "topic=$topic number=$number ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
        }
        val missing = bed.revisit("cs/os/nothing-here", "1", bed.visitorCookie(token))
        assertEquals(404, missing.status)
        assertEquals("not_found", bed.errorCode(missing))
        assertEquals(emptyList<String>(), bed.fake.calls)
    }
}
