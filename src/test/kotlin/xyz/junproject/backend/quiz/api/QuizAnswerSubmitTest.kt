package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.api.VisitorCookie
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.domain.QuizSplitter
import java.time.Duration
import java.util.UUID

/**
 * 명세 18.1 · 18.2 · 12 · 11.2 — 답을 냄 `POST /api/quiz/answers`.
 * 저장되면 그 응답에 그 질문의 정답만 · 첫 답에서 쿠키 발급(아는 방문자는 기한 늘리기) · 저장 장애와 속도 제한이면 정답은 싣되 `saved: false` ·
 * 확인 불가 응답에는 `Set-Cookie` 없음 · 잘못된 입력은 저장소에 닿기 전에 거절하고 정답을 싣지 않음 · 질문이 바뀌었으면 저장하지 않음.
 */
class QuizAnswerSubmitTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    @Suppress("UNCHECKED_CAST")
    private fun correctAnswerOf(data: Map<String, Any?>) = data["correct_answer"] as Map<String, Any?>?

    @Suppress("UNCHECKED_CAST")
    private fun attemptOf(data: Map<String, Any?>) = data["attempt"] as Map<String, Any?>?

    private fun assertNoAnswerText(text: String) =
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(text.contains(marker), "정답 자료($marker)가 응답에 있다: $text") }

    /** 질문 2 의 정답만 있고 다른 번호의 정답 글은 없다 */
    private fun assertOnlyQuestionTwoAnswer(text: String) {
        assertTrue(text.contains("ANSWER-MARK-2"), text)
        listOf("ANSWER-MARK-1", "ANSWER-MARK-3", "ANSWER-TITLE-1", "ANSWER-TITLE-3").forEach { other ->
            assertFalse(text.contains(other), "다른 질문의 정답($other)이 응답에 있다: $text")
        }
    }

    // ── 저장 · 정답 ───────────────────────────────────────

    @Test
    fun `첫 답이 저장되고 그 응답에 그 질문의 정답만 실린다`() {
        val attemptId = UUID.randomUUID()
        val response = bed.submit(bed.answerBody(number = 2, answer = "스레드는 실행 흐름", attemptId = attemptId.toString()))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(true, data["saved"])
        assertEquals(null, data["not_saved_reason"])
        val expected = QuizContent.processSplit.answerOf(2)!!
        assertEquals(mapOf("number" to 2, "title" to expected.title, "text" to expected.text), correctAnswerOf(data))
        assertOnlyQuestionTwoAnswer(response.contentAsString)
        assertEquals(mapOf("attempt_id" to attemptId.toString(), "topic" to TOPIC, "question_number" to 2,
            "submitted_at" to "2026-11-25T01:00:00Z", "grade" to null, "graded_at" to null), attemptOf(data))

        val stored = bed.fake.attempts.values.single()
        assertEquals(attemptId, stored.attemptId)
        assertEquals("스레드는 실행 흐름", stored.answer)
        assertEquals(QuizContent.hashOf(TOPIC, 2), stored.questionHash)
        assertEquals(QuizContent.HEAD, stored.contentSha)
    }

    @Test
    fun `첫 답이면 study_visitor 쿠키를 VisitorCookie 의 속성 그대로 발급한다`() {
        val response = bed.submit(bed.answerBody())
        val cookies = bed.setCookies(response)
        val issued = bed.issuedTokens.single()
        bed.assertSameCookie(VisitorCookie.issue(issued), cookies[VisitorCookie.NAME])
        val attributes = bed.attributesOf(cookies.getValue(VisitorCookie.NAME))
        assertTrue(attributes.containsKey("httponly") && attributes.containsKey("secure"), attributes.toString())
        assertEquals("lax", attributes["samesite"]?.lowercase())
        assertEquals("/", attributes["path"])
        assertEquals("31536000", attributes["max-age"])
        assertTrue(VisitorToken.hash(issued) in bed.fake.visitorsByHash.keys)
        assertFalse(response.contentAsString.contains(issued), "토큰이 응답 본문에 있다")
    }

    @Test
    fun `아는 방문자도 쿠키가 제시된 토큰 그대로 다시 놓여 기한이 늘어난다`() {
        val (token, _) = bed.knownVisitor()
        val response = bed.submit(bed.answerBody(), bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, bed.data(response)["saved"])
        bed.assertSameCookie(VisitorCookie.issue(token), bed.setCookies(response)[VisitorCookie.NAME])
        assertEquals(1, bed.fake.visitorsByHash.size, "새 방문자를 만들었다")
        assertEquals(0, bed.issuedTokens.size)
        assertFalse(response.contentAsString.contains(token), "토큰이 응답 본문에 있다")
    }

    @Test
    fun `같은 시도 번호를 다시 보내면 같은 결과와 정답이 돌아오고 한 줄만 남는다`() {
        val (token, _) = bed.knownVisitor()
        val body = bed.answerBody(attemptId = UUID.randomUUID().toString())
        val first = bed.submit(body, bed.visitorCookie(token))
        bed.clock.advance(Duration.ofSeconds(5))
        val again = bed.submit(body, bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        assertEquals(bed.data(first), bed.data(again))
        assertEquals("2026-11-25T01:00:00Z", attemptOf(bed.data(again))!!["submitted_at"])
        assertOnlyQuestionTwoAnswer(again.contentAsString)
        assertEquals(1, bed.fake.attempts.size)
    }

    @Test
    fun `같은 시도 번호를 다른 본문으로 다시 보내면 처음 저장된 것과 그 질문의 정답을 돌려주고 새 줄이 생기지 않는다`() {
        val (token, _) = bed.knownVisitor()
        val attemptId = UUID.randomUUID().toString()
        val first = bed.submit(bed.answerBody(number = 2, answer = "처음 쓴 답", attemptId = attemptId), bed.visitorCookie(token))
        bed.clock.advance(Duration.ofSeconds(5))
        val different = bed.submit(bed.answerBody(number = 1, answer = "다르게 쓴 답", attemptId = attemptId), bed.visitorCookie(token))

        assertEquals(200, different.status, different.contentAsString)
        val data = bed.data(different)
        assertEquals(true, data["saved"])
        assertEquals(attemptOf(bed.data(first)), attemptOf(data))
        assertEquals(2, attemptOf(data)!!["question_number"])
        assertEquals(2, correctAnswerOf(data)!!["number"])
        assertOnlyQuestionTwoAnswer(different.contentAsString)
        assertEquals(1, bed.fake.attempts.size)
        assertEquals("처음 쓴 답", bed.fake.attempts.values.single().answer)
        assertFalse(different.contentAsString.contains("다르게 쓴 답"))
    }

    // ── 저장하지 못함 — 정답은 싣는다 ─────────────────────────

    @Test
    fun `쿠키 없는 첫 답의 저장소가 죽으면 정답은 싣고 saved false 이며 Set-Cookie 가 없다`() {
        bed.fake.failing += "recordFirstAttempt"
        val response = bed.submit(bed.answerBody())
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(false, data["saved"])
        assertEquals("storage_unavailable", data["not_saved_reason"])
        assertEquals(null, attemptOf(data))
        assertOnlyQuestionTwoAnswer(response.contentAsString)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `아는 방문자를 확인할 수 없으면 정답은 싣고 saved false 이며 쿠키를 새로 놓지도 지우지도 않는다`() {
        val (token, _) = bed.knownVisitor()
        bed.fake.failing += "findVisitor"
        val response = bed.submit(bed.answerBody(), bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(false, data["saved"])
        assertEquals("storage_unavailable", data["not_saved_reason"])
        assertOnlyQuestionTwoAnswer(response.contentAsString)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        assertEquals(0, bed.issuedTokens.size)
    }

    @Test
    fun `하루 상한에 닿으면 저장하지 않고 saved false 와 daily_limit_reached 이며 정답은 싣는다`() {
        val (token, visitor) = bed.knownVisitor()
        repeat(1_000) { bed.putAttempt(visitor, TOPIC, 1, QuizTestBed.NOW.minusSeconds(60)) }
        val response = bed.submit(bed.answerBody(), bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(false, data["saved"])
        assertEquals("daily_limit_reached", data["not_saved_reason"])
        assertOnlyQuestionTwoAnswer(response.contentAsString)
        assertEquals(1_000, bed.fake.attempts.size)
    }

    // ── 거절 — 정답을 싣지 않는다 ─────────────────────────────

    @Test
    fun `시도 번호가 이미 남의 것이면 409 attempt_id_taken 이고 정답을 싣지 않으며 방문자를 만들지 않는다`() {
        val (_, owner) = bed.knownVisitor()
        val taken = bed.putAttempt(owner, TOPIC, 2, QuizTestBed.NOW)
        val response = bed.submit(bed.answerBody(attemptId = taken.toString()))
        assertEquals(409, response.status, response.contentAsString)
        assertEquals("attempt_id_taken", bed.errorCode(response))
        assertNoAnswerText(response.contentAsString)
        assertFalse(response.contentAsString.contains("준비한 답"), "남의 답이 응답에 있다")
        assertEquals(1, bed.fake.visitorsByHash.size)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `답이 4000자면 저장되고 4001자면 저장소에 닿기 전에 422 answer_too_long 이며 정답을 싣지 않는다`() {
        val tooLong = bed.submit(bed.answerBody(answer = "가".repeat(4_001)))
        assertEquals(422, tooLong.status, tooLong.contentAsString.take(300))
        assertEquals("answer_too_long", bed.errorCode(tooLong))
        assertNoAnswerText(tooLong.contentAsString)
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(emptyList<String>(), tooLong.getHeaders("Set-Cookie"))

        val atLimit = bed.submit(bed.answerBody(answer = "가".repeat(4_000)))
        assertEquals(200, atLimit.status)
        assertEquals(true, bed.data(atLimit)["saved"])
    }

    @Test
    fun `저장할 수 없는 글자 NUL 이 든 답은 422 invalid_request 이고 정답을 싣지 않으며 저장소에 닿지 않는다`() {
        val response = bed.submit(bed.answerBody(answer = "답\u0000끝"))
        assertEquals(422, response.status, response.contentAsString)
        assertEquals("invalid_request", bed.errorCode(response))
        assertNoAnswerText(response.contentAsString)
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `빈 답과 공백뿐인 답은 저장소에 닿기 전에 422 로 거절하고 정답을 싣지 않는다`() {
        listOf("", "   ", " \n\t \r\n").forEach { blank ->
            val response = bed.submit(bed.answerBody(answer = blank))
            assertEquals(422, response.status, "answer=[$blank] ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
        }
        val missing = bed.submit(bed.answerBody().minus("answer"))
        assertEquals(422, missing.status, missing.contentAsString)
        assertNoAnswerText(missing.contentAsString)
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(0, bed.issuedTokens.size)
    }

    @Test
    fun `UUID 가 아닌 시도 번호는 저장소에 닿기 전에 422 로 거절하고 정답을 싣지 않는다`() {
        listOf("not-a-uuid", "", "123", null).forEach { attemptId ->
            val response = bed.submit(bed.answerBody(attemptId = attemptId))
            assertEquals(422, response.status, "attempt_id=$attemptId ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
        }
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `그 주제에 없는 질문 번호는 저장소에 닿기 전에 422 로 거절하고 정답을 싣지 않는다`() {
        listOf(0, 4, 99, -1).forEach { number ->
            val response = bed.submit(bed.answerBody(number = 1).plus("question_number" to number))
            assertEquals(422, response.status, "number=$number ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
        }
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `잘못된 주제 경로는 422 이고 없는 주제는 404 이며 둘 다 저장소에 닿지 않는다`() {
        listOf("cs/../${QuizContent.OUTSIDE_TOPIC}", QuizContent.OUTSIDE_TOPIC, "$TOPIC\nx", "$TOPIC:x", "/$TOPIC").forEach { topic ->
            val response = bed.submit(bed.answerBody().plus("topic" to topic))
            assertEquals(422, response.status, "topic=$topic ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
        }
        val missing = bed.submit(bed.answerBody().plus("topic" to "cs/os/nothing-here"))
        assertEquals(404, missing.status, missing.contentAsString)
        assertEquals("not_found", bed.errorCode(missing))
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `커밋 꼴이 아닌 내용 SHA 와 해시 꼴이 아닌 질문 해시는 git 에 넘기지 않고 422 로 거절한다`() {
        listOf("--output=/tmp/x", "HEAD", "abc", "g".repeat(40), "${QuizContent.HEAD}\n").forEach { sha ->
            val response = bed.submit(bed.answerBody(contentSha = sha))
            assertEquals(422, response.status, "sha=$sha ${response.contentAsString}")
            assertEquals("invalid_request", bed.errorCode(response))
            assertNoAnswerText(response.contentAsString)
        }
        listOf("abc", "Z".repeat(64), QuizContent.hashOf(TOPIC, 2).uppercase()).forEach { hash ->
            val response = bed.submit(bed.answerBody(questionHash = hash))
            assertEquals(422, response.status, "hash=$hash ${response.contentAsString}")
            assertNoAnswerText(response.contentAsString)
        }
        assertTrue(bed.notes.readsAt.none { (sha, _) -> sha != QuizContent.HEAD }, bed.notes.readsAt.toString())
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `쪼갤 수 없는 주제에 답을 내면 저장소에 닿지 않고 409 quiz_unavailable 이며 정답과 쿠키가 없다`() {
        val brokenQuestionOne = QuizSplitter.questionHash("1. QUESTION-MARK-BROKEN-1 첫 질문")
        val response = bed.submit(bed.answerBody(number = 1, topic = QuizContent.BROKEN_TOPIC, questionHash = brokenQuestionOne))
        assertEquals(409, response.status, response.contentAsString)
        assertEquals("quiz_unavailable", bed.errorCode(response))
        assertNoAnswerText(response.contentAsString)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(0, bed.issuedTokens.size)
    }

    // ── 어느 판의 질문인가 ────────────────────────────────────

    @Test
    fun `받은 해시가 받은 SHA 의 파일에서 다시 낸 해시와 다르면 저장하지 않고 409 question_changed 이며 정답을 싣지 않는다`() {
        val response = bed.submit(bed.answerBody(contentSha = QuizContent.HEAD, questionHash = QuizContent.oldQuestionTwoHash))
        assertEquals(409, response.status, response.contentAsString)
        assertEquals("question_changed", bed.errorCode(response))
        assertNoAnswerText(response.contentAsString)
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `받은 SHA 를 읽을 수 없으면 지금 판과 견줘 다르면 question_changed 로 저장하지 않는다`() {
        val response = bed.submit(bed.answerBody(contentSha = QuizContent.UNREADABLE_SHA, questionHash = QuizContent.oldQuestionTwoHash))
        assertEquals(409, response.status, response.contentAsString)
        assertEquals("question_changed", bed.errorCode(response))
        assertNoAnswerText(response.contentAsString)
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `받은 SHA 를 읽을 수 없어도 지금 판과 같으면 저장하고 지금 판의 정답을 싣는다`() {
        val response = bed.submit(bed.answerBody(contentSha = QuizContent.UNREADABLE_SHA, questionHash = QuizContent.hashOf(TOPIC, 2)))
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, bed.data(response)["saved"])
        assertOnlyQuestionTwoAnswer(response.contentAsString)
    }

    @Test
    fun `옛 SHA 의 질문에 맞게 낸 답은 저장되고 그 판의 정답이 실린다`() {
        val response = bed.submit(bed.answerBody(contentSha = QuizContent.OLD_SHA, questionHash = QuizContent.oldQuestionTwoHash))
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, bed.data(response)["saved"])
        val text = response.contentAsString
        assertTrue(text.contains("ANSWER-MARK-OLD-2"), text)
        assertFalse(text.contains("ANSWER-MARK-2"), "지금 판의 정답이 실렸다: $text")
        val stored = bed.fake.attempts.values.single()
        assertEquals(QuizContent.oldQuestionTwoHash, stored.questionHash)
        assertEquals(QuizContent.OLD_SHA, stored.contentSha)
    }

    @Test
    fun `받은 SHA 가 지금 판이어도 작업 트리를 읽지 않고 그 판을 지정해 읽는다`() {
        val response = bed.submit(bed.answerBody(contentSha = QuizContent.HEAD))
        assertEquals(true, bed.data(response)["saved"], response.contentAsString)
        assertEquals(emptyList<String>(), bed.notes.reads, "퀴즈 경로가 작업 트리를 읽었다")
        assertTrue((QuizContent.HEAD to "$TOPIC/1-question.md") in bed.notes.readsAt, bed.notes.readsAt.toString())
        assertTrue(bed.notes.readsAt.all { (sha, _) -> sha == QuizContent.HEAD }, bed.notes.readsAt.toString())
    }

    @Test
    fun `받은 SHA 가 지금 판이 아니면 그 판에서 읽는다`() {
        bed.submit(bed.answerBody(contentSha = QuizContent.OLD_SHA, questionHash = QuizContent.oldQuestionTwoHash))
        assertTrue((QuizContent.OLD_SHA to "$TOPIC/1-question.md") in bed.notes.readsAt, bed.notes.readsAt.toString())
    }

    @Test
    fun `받은 SHA 를 읽지 못해 지금 판으로 견줘 저장했으면 지금 판의 SHA 를 저장한다`() {
        val response = bed.submit(bed.answerBody(contentSha = QuizContent.UNREADABLE_SHA, questionHash = QuizContent.hashOf(TOPIC, 2)))
        assertEquals(true, bed.data(response)["saved"], response.contentAsString)
        assertEquals(QuizContent.HEAD, bed.fake.attempts.values.single().contentSha)
        assertTrue((QuizContent.HEAD to "$TOPIC/1-question.md") in bed.notes.readsAt, "지금 판의 SHA 로 읽지 않았다: ${bed.notes.readsAt}")
        assertEquals(emptyList<String>(), bed.notes.reads, "퀴즈 경로가 작업 트리를 읽었다")
    }

    @Test
    fun `어느 판에도 없는 해시는 question_changed 다`() {
        val response = bed.submit(bed.answerBody(questionHash = QuizContent.UNKNOWN_HASH))
        assertEquals(409, response.status)
        assertEquals("question_changed", bed.errorCode(response))
        assertEquals(emptyList<String>(), bed.fake.calls)
    }

    @Test
    fun `확인 불가 응답에서 쿠키를 지우지도 않는다 - chat_session 을 포함해 Set-Cookie 가 하나도 없다`() {
        val (token, _) = bed.knownVisitor()
        bed.fake.failing += "findVisitor"
        val response = bed.submit(bed.answerBody(), bed.visitorCookie(token),
            jakarta.servlet.http.Cookie(QuizTestBed.CHAT_COOKIE, "123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(false, bed.data(response)["saved"])
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }
}
