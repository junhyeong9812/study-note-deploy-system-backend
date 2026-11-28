package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import java.util.UUID

/**
 * 명세 18.1 · 11.2 · 14 — 채점 `POST /api/quiz/grades`: 스스로 고른 결과를 그 시도에 덧붙인다(둘째 채점은 첫 채점을 돌려준다).
 * 남의 시도 · 없는 시도 · 쿠키 없음 · 잘못된 결과 값 · 저장 장애(쿠키를 건드리지 않는다).
 */
class QuizGradeTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    @Suppress("UNCHECKED_CAST")
    private fun attemptOf(response: org.springframework.mock.web.MockHttpServletResponse) = bed.data(response)["attempt"] as Map<String, Any?>

    private fun gradeBody(attemptId: Any?, grade: Any?) = linkedMapOf("attempt_id" to attemptId, "grade" to grade)

    @Test
    fun `스스로 고른 결과가 그 시도에 덧붙는다`() {
        val (token, visitor) = bed.knownVisitor()
        val attemptId = bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        val response = bed.grade(gradeBody(attemptId.toString(), "CORRECT"), bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val attempt = attemptOf(response)
        assertEquals(attemptId.toString(), attempt["attempt_id"])
        assertEquals(TOPIC, attempt["topic"])
        assertEquals(2, attempt["question_number"])
        assertEquals("CORRECT", attempt["grade"])
        assertEquals("2026-11-25T01:00:00Z", attempt["graded_at"])
        assertEquals(Grade.CORRECT, bed.fake.attempts.getValue(attemptId).grade)
    }

    @Test
    fun `둘째 채점은 받지 않고 첫 채점을 돌려준다`() {
        val (token, visitor) = bed.knownVisitor()
        val attemptId = bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        bed.grade(gradeBody(attemptId.toString(), "WRONG"), bed.visitorCookie(token))
        val second = bed.grade(gradeBody(attemptId.toString(), "CORRECT"), bed.visitorCookie(token))
        assertEquals(200, second.status, second.contentAsString)
        assertEquals("WRONG", attemptOf(second)["grade"])
        assertEquals(Grade.WRONG, bed.fake.attempts.getValue(attemptId).grade)
    }

    @Test
    fun `남의 시도를 채점하면 404 attempt_not_found 이고 그 시도는 그대로다`() {
        val (_, owner) = bed.knownVisitor()
        val ownersAttempt = bed.putAttempt(owner, TOPIC, 2, QuizTestBed.NOW, answer = "주인의 답")
        val (otherToken, _) = bed.knownVisitor()
        val response = bed.grade(gradeBody(ownersAttempt.toString(), "CORRECT"), bed.visitorCookie(otherToken))
        assertEquals(404, response.status, response.contentAsString)
        assertEquals("attempt_not_found", bed.errorCode(response))
        assertFalse(response.contentAsString.contains("주인의 답"))
        assertEquals(null, bed.fake.attempts.getValue(ownersAttempt).grade)
    }

    @Test
    fun `없는 시도는 404 attempt_not_found 다`() {
        val (token, _) = bed.knownVisitor()
        val response = bed.grade(gradeBody(UUID.randomUUID().toString(), "CORRECT"), bed.visitorCookie(token))
        assertEquals(404, response.status, response.contentAsString)
        assertEquals("attempt_not_found", bed.errorCode(response))
    }

    @Test
    fun `쿠키가 없거나 모르는 토큰이면 404 visitor_unknown 이고 방문자를 만들지 않는다`() {
        val (_, owner) = bed.knownVisitor()
        val attemptId = bed.putAttempt(owner, TOPIC, 2, QuizTestBed.NOW)
        listOf(bed.grade(gradeBody(attemptId.toString(), "CORRECT")),
            bed.grade(gradeBody(attemptId.toString(), "CORRECT"), bed.visitorCookie("Z".repeat(43)))).forEach { response ->
            assertEquals(404, response.status, response.contentAsString)
            assertEquals("visitor_unknown", bed.errorCode(response))
            assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        }
        assertEquals(1, bed.fake.visitorsByHash.size)
        assertEquals(0, bed.issuedTokens.size)
        assertEquals(null, bed.fake.attempts.getValue(attemptId).grade)
    }

    @Test
    fun `잘못된 결과 값과 UUID 가 아닌 시도 번호는 저장소에 닿기 전에 422 로 거절한다`() {
        val (token, visitor) = bed.knownVisitor()
        val attemptId = bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        listOf(
            gradeBody(attemptId.toString(), "GREAT"),
            gradeBody(attemptId.toString(), "correct"),
            gradeBody(attemptId.toString(), ""),
            gradeBody(attemptId.toString(), null),
            gradeBody("not-a-uuid", "CORRECT"),
            gradeBody(null, "CORRECT"),
        ).forEach { body ->
            val response = bed.grade(body, bed.visitorCookie(token))
            assertEquals(422, response.status, "$body ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response), body.toString())
        }
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(null, bed.fake.attempts.getValue(attemptId).grade)
    }

    @Test
    fun `채점을 넣다 저장소가 죽으면 503 storage_unavailable 이고 쿠키를 건드리지 않는다`() {
        val (token, visitor) = bed.knownVisitor()
        val attemptId = bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        bed.fake.failing += "recordGrade"
        val response = bed.grade(gradeBody(attemptId.toString(), "CORRECT"), bed.visitorCookie(token))
        assertEquals(503, response.status, response.contentAsString)
        assertEquals("storage_unavailable", bed.errorCode(response))
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `방문자를 확인할 수 없으면 503 storage_unavailable 이고 쿠키를 건드리지 않는다`() {
        val (token, visitor) = bed.knownVisitor()
        val attemptId = bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        bed.fake.failing += "findVisitor"
        val response = bed.grade(gradeBody(attemptId.toString(), "CORRECT"), bed.visitorCookie(token))
        assertEquals(503, response.status, response.contentAsString)
        assertEquals("storage_unavailable", bed.errorCode(response))
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }
}
