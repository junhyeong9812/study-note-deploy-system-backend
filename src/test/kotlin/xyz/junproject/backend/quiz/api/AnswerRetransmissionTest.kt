package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import java.util.UUID

/**
 * 명세 20 — 이미 저장된 시도의 재전송은 판 확인보다 먼저 알아본다: 아는 방문자가 응답을 잃고 같은 시도 번호로 다시 내면, 그 사이 질문이
 * 바뀌었거나 그 판을 더는 읽을 수 없어도 처음 저장된 것을 돌려준다. 입력의 꼴 검증은 여전히 먼저 · 남의 시도 번호와 쿠키 없는 요청에는 이 길이 없다.
 */
class AnswerRetransmissionTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    @Suppress("UNCHECKED_CAST")
    private fun attemptOf(response: org.springframework.mock.web.MockHttpServletResponse) = bed.data(response)["attempt"] as Map<String, Any?>?

    /** 내용이 바뀐다 — 새 판으로 옮기고 질문 2 의 글이 달라진다. 처음 판(HEAD)은 더는 읽을 수 없다(얕은 사본) */
    private fun contentChangesAndOldShaIsGone(questions: String = QuizContent.OLD_PROCESS_QUESTIONS, answers: String = QuizContent.OLD_PROCESS_ANSWERS) {
        bed.notes.head = QuizContent.NEW_SHA
        bed.notes.current["$TOPIC/1-question.md"] = questions
        bed.notes.current["$TOPIC/3-answer.md"] = answers
    }

    private val body = { bed.answerBody(number = 2, answer = "처음 쓴 답", attemptId = attemptId.toString()) }
    private val attemptId = UUID.randomUUID()

    @Test
    fun `저장한 뒤 질문이 바뀌고 그 판을 읽을 수 없어도 같은 요청의 재전송은 처음 저장된 것을 돌려주고 새 줄이 생기지 않는다`() {
        val (token, _) = bed.knownVisitor()
        val first = bed.submit(body(), bed.visitorCookie(token))
        assertEquals(true, bed.data(first)["saved"], first.contentAsString)
        contentChangesAndOldShaIsGone()

        val again = bed.submit(body(), bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        assertEquals(true, bed.data(again)["saved"])
        assertEquals(attemptOf(first), attemptOf(again))
        assertEquals(1, bed.fake.attempts.size)
        assertEquals("처음 쓴 답", bed.fake.attempts.values.single().answer)
    }

    @Test
    fun `저장한 뒤 그 질문 번호가 없어져도 재전송은 거절이 아니고 처음 저장된 것을 돌려준다`() {
        val (token, _) = bed.knownVisitor()
        val first = bed.submit(body(), bed.visitorCookie(token))
        val withoutTwo = QuizContent.PROCESS_QUESTIONS.replace("2. QUESTION-MARK-2 스레드와 프로세스는 어떻게 다른가?\n", "")
        val answersWithoutTwo = QuizContent.PROCESS_ANSWERS.replace("### 2. ANSWER-TITLE-2 자원의 공유\n\nANSWER-MARK-2 스레드는 주소 공간을 함께 쓴다.\n\n", "")
        assertNotEquals(QuizContent.PROCESS_QUESTIONS, withoutTwo)
        assertNotEquals(QuizContent.PROCESS_ANSWERS, answersWithoutTwo)
        contentChangesAndOldShaIsGone(withoutTwo, answersWithoutTwo)

        val again = bed.submit(body(), bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        assertEquals(true, bed.data(again)["saved"])
        assertEquals(attemptOf(first), attemptOf(again))
        assertEquals(1, bed.fake.attempts.size)
    }

    @Test
    fun `저장된 시도 번호여도 꼴이 틀린 입력은 저장소에 닿기 전에 먼저 거절한다`() {
        val (token, _) = bed.knownVisitor()
        bed.submit(body(), bed.visitorCookie(token))
        contentChangesAndOldShaIsGone()
        bed.fake.calls.clear()

        listOf(
            body().plus("content_sha" to "--output=/tmp/x"),
            body().plus("answer" to "  "),
            body().plus("topic" to "cs/../x"),
            body().plus("question_hash" to "abc"),
        ).forEach { malformed ->
            val response = bed.submit(malformed, bed.visitorCookie(token))
            assertEquals(422, response.status, "$malformed ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
        }
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `남의 시도 번호로는 이 길이 열리지 않아 남의 저장된 것이 돌아오지 않는다`() {
        val (ownerToken, _) = bed.knownVisitor()
        bed.submit(bed.answerBody(number = 2, answer = "주인의 답", attemptId = attemptId.toString()), bed.visitorCookie(ownerToken))
        contentChangesAndOldShaIsGone()
        val (otherToken, _) = bed.knownVisitor()

        val response = bed.submit(body(), bed.visitorCookie(otherToken))

        assertEquals(409, response.status, response.contentAsString)
        assertTrue(bed.errorCode(response) in setOf("attempt_id_taken", "question_changed"), response.contentAsString)
        assertFalse(response.contentAsString.contains("주인의 답"))
        QuizContent.ANSWER_MARKERS.forEach { assertFalse(response.contentAsString.contains(it), response.contentAsString) }
        assertEquals(1, bed.fake.attempts.size)
        assertEquals("주인의 답", bed.fake.attempts.values.single().answer)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `쿠키 없는 요청과 모르는 토큰은 이 길을 타지 않아 바뀐 질문에 저장하지 않는다`() {
        val (token, _) = bed.knownVisitor()
        bed.submit(body(), bed.visitorCookie(token))
        contentChangesAndOldShaIsGone()

        listOf(bed.submit(body()), bed.submit(body(), bed.visitorCookie("Z".repeat(43)))).forEach { response ->
            assertEquals(409, response.status, response.contentAsString)
            assertTrue(bed.errorCode(response) in setOf("attempt_id_taken", "question_changed"), response.contentAsString)
            assertFalse(response.contentAsString.contains("처음 쓴 답"))
            assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        }
        assertEquals(1, bed.fake.attempts.size)
        assertEquals(1, bed.fake.visitorsByHash.size)
        assertEquals(0, bed.issuedTokens.size)
    }
}
