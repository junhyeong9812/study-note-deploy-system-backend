package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.domain.QuizSplitResult
import xyz.junproject.backend.quiz.domain.QuizSplitter
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
        bed.notes.commit(QuizContent.NEW_SHA, mapOf("$TOPIC/1-question.md" to questions, "$TOPIC/3-answer.md" to answers))
        bed.notes.forget(QuizContent.HEAD)
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
        val (_, visitor) = bed.knownVisitor()
        storedWithoutReadingContent(visitor)
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

    // ── 재전송의 정답은 답한 그 질문의 것만(명세 21) ────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun correctAnswerOf(response: org.springframework.mock.web.MockHttpServletResponse) =
        bed.data(response)["correct_answer"] as Map<String, Any?>?

    private fun assertNoAnswerText(text: String) =
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(text.contains(marker), "정답 자료($marker)가 응답에 있다: $text") }

    /** 처음 판(HEAD)의 글을 git 에서 계속 읽을 수 있게 둔다 */
    private fun keepOldShaReadable() {
        bed.notes.history[QuizContent.HEAD to "$TOPIC/1-question.md"] = QuizContent.PROCESS_QUESTIONS
        bed.notes.history[QuizContent.HEAD to "$TOPIC/3-answer.md"] = QuizContent.PROCESS_ANSWERS
    }

    /**
     * 처음 판을 한 번도 읽지 않은 채 저장된 이 테스트의 시도(질문 2, 처음 판 HEAD) — 그 판의 응답에 실렸을 attempt.
     * 읽은 판은 기억되므로(명세 23 — 판의 글은 바뀌지 않는다) "그 판을 더는 읽을 수 없음"을 보는 테스트는 이것으로 시작한다.
     */
    private fun storedWithoutReadingContent(visitor: xyz.junproject.backend.progress.usecase.Visitor): Map<String, Any?> {
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW, answer = "처음 쓴 답", attemptId = attemptId)
        assertEquals(emptyList<Pair<String, String>>(), bed.notes.readsAt)
        return mapOf("attempt_id" to attemptId.toString(), "topic" to TOPIC, "question_number" to 2,
            "submitted_at" to "2026-11-25T01:00:00Z", "grade" to null, "graded_at" to null)
    }

    @Test
    fun `옛 판을 읽을 수 없고 질문이 바뀌었으면 재전송은 저장됨이지만 정답 칸이 비고 정답 글이 하나도 없다`() {
        val (token, visitor) = bed.knownVisitor()
        val first = storedWithoutReadingContent(visitor)
        contentChangesAndOldShaIsGone()   // 지금 판의 질문 2 는 다른 글 · 정답은 ANSWER-MARK-OLD-2

        val again = bed.submit(body(), bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        val data = bed.data(again)
        assertEquals(true, data["saved"])
        assertEquals(first, attemptOf(again))
        assertTrue(data.containsKey("correct_answer"), data.toString())
        assertNull(data["correct_answer"], "바뀐 질문의 정답이 나갔다: ${again.contentAsString}")
        assertNoAnswerText(again.contentAsString)
        assertEquals(1, bed.fake.attempts.size)
    }

    @Test
    fun `옛 판이 읽히면 질문이 지금 바뀌었어도 재전송에는 그 판의 정답이 실린다`() {
        val (token, _) = bed.knownVisitor()
        bed.submit(body(), bed.visitorCookie(token))
        contentChangesAndOldShaIsGone()
        keepOldShaReadable()

        val again = bed.submit(body(), bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        val expected = (QuizSplitter.split(QuizContent.PROCESS_QUESTIONS, QuizContent.PROCESS_ANSWERS) as QuizSplitResult.Split).answerOf(2)!!
        assertEquals(mapOf("number" to 2, "title" to expected.title, "text" to expected.text), correctAnswerOf(again))
        assertFalse(again.contentAsString.contains("ANSWER-MARK-OLD-2"), "지금 판의 새 정답이 실렸다")
    }

    @Test
    fun `옛 판을 읽을 수 없어도 질문 글이 그대로면 재전송에는 지금 판의 그 질문의 정답이 실린다`() {
        val (token, visitor) = bed.knownVisitor()
        storedWithoutReadingContent(visitor)
        val newAnswers = QuizContent.PROCESS_ANSWERS.replace("ANSWER-MARK-2 스레드는 주소 공간을 함께 쓴다.", "ANSWER-MARK-NEW-2 고친 정답.")
        contentChangesAndOldShaIsGone(QuizContent.PROCESS_QUESTIONS, newAnswers)

        val again = bed.submit(body(), bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        assertEquals(2, correctAnswerOf(again)!!["number"])
        assertTrue(again.contentAsString.contains("ANSWER-MARK-NEW-2"), again.contentAsString)
        listOf("ANSWER-MARK-1", "ANSWER-MARK-3").forEach { assertFalse(again.contentAsString.contains(it), again.contentAsString) }
    }

    @Test
    fun `재전송 요청의 주제와 번호가 저장된 것과 달라도 저장된 질문의 정답만 실린다`() {
        val (token, _) = bed.knownVisitor()
        bed.submit(body(), bed.visitorCookie(token))

        val other = bed.answerBody(number = 1, answer = "다른 주제의 답", attemptId = attemptId.toString(), topic = QuizContent.OTHER_TOPIC,
            questionHash = QuizContent.hashOf(QuizContent.OTHER_TOPIC, 1))
        val again = bed.submit(other, bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        assertEquals(2, correctAnswerOf(again)!!["number"])
        assertTrue(again.contentAsString.contains("ANSWER-MARK-2"), again.contentAsString)
        listOf("TCP", "ANSWER-MARK-1", "ANSWER-MARK-3").forEach { assertFalse(again.contentAsString.contains(it), "$it: ${again.contentAsString}") }
        assertEquals(TOPIC, attemptOf(again)!!["topic"])
        assertEquals(1, bed.fake.attempts.size)
    }

    @Test
    fun `저장된 주제가 없어졌거나 쪼갤 수 없게 되면 재전송은 저장됨이고 정답 칸이 빈다`() {
        val (token, visitor) = bed.knownVisitor()
        val first = storedWithoutReadingContent(visitor)
        val unsplittable = QuizContent.PROCESS_ANSWERS.substringBefore("### 2.")   // 정답 2 · 3 이 모자람
        contentChangesAndOldShaIsGone(QuizContent.PROCESS_QUESTIONS, unsplittable)

        val whenUnsplittable = bed.submit(body(), bed.visitorCookie(token))
        assertEquals(200, whenUnsplittable.status, whenUnsplittable.contentAsString)
        assertEquals(true, bed.data(whenUnsplittable)["saved"])
        assertEquals(first, attemptOf(whenUnsplittable))
        assertNull(bed.data(whenUnsplittable)["correct_answer"], whenUnsplittable.contentAsString)

        bed.notes.commit(QuizContent.NEWER_SHA, mapOf("$TOPIC/1-question.md" to null, "$TOPIC/2-summary.md" to null, "$TOPIC/3-answer.md" to null))
        val whenGone = bed.submit(body(), bed.visitorCookie(token))
        assertEquals(200, whenGone.status, whenGone.contentAsString)
        assertEquals(first, attemptOf(whenGone))
        assertNull(bed.data(whenGone)["correct_answer"], whenGone.contentAsString)
        assertNoAnswerText(whenGone.contentAsString)
        assertEquals(1, bed.fake.attempts.size)
    }

    // ── 동기화의 틈 — 퀴즈 경로는 내용을 늘 판으로 읽는다(명세 22) ─────────────

    /** 판마다의 글 — 저장된 content_sha 의 판에서 질문 · 정답을 다시 꺼내 응답 · 기록과 견준다 */
    private fun splitAt(sha: String): QuizSplitResult.Split {
        val files = bed.notes.filesAt(sha)
        return QuizSplitter.split(files.getValue("$TOPIC/1-question.md"), files.getValue("$TOPIC/3-answer.md")) as QuizSplitResult.Split
    }

    /**
     * 실제 틈: 내용 동기화가 작업 트리의 파일은 새 판(NEW_SHA)으로 이미 바꿨는데 지금 판의 표시는 아직 HEAD 다
     * (`git reset --hard` 는 파일을 먼저 바꾸고 표시를 나중에 옮긴다 — 그 사이에는 currentHead() 를 몇 번 읽어도 HEAD).
     */
    private fun workingTreeAheadOfHead(newQuestions: String, newAnswers: String) {
        bed.notes.commit(QuizContent.NEW_SHA, mapOf("$TOPIC/1-question.md" to newQuestions, "$TOPIC/3-answer.md" to newAnswers), moveHead = false)
        assertEquals(QuizContent.HEAD, bed.notes.currentHead())
        assertEquals(newQuestions, bed.notes.current["$TOPIC/1-question.md"])
    }

    /** 저장된 시도의 판 · 해시 · 응답의 정답이 서로 맞는다 */
    private fun assertConsistentWithStoredVersion(response: org.springframework.mock.web.MockHttpServletResponse) {
        val stored = bed.fake.attempts.values.single()
        val version = splitAt(stored.contentSha)
        assertEquals(version.questions.single { it.number == stored.questionNumber }.hash, stored.questionHash,
            "저장된 판(${stored.contentSha})에 없는 해시가 기록에 남았다")
        val expected = version.answerOf(stored.questionNumber)!!
        assertEquals(mapOf("number" to expected.number, "title" to expected.title, "text" to expected.text), correctAnswerOf(response),
            "응답의 정답이 저장된 판(${stored.contentSha})의 정답과 다르다")
    }

    @Test
    fun `작업 트리가 지금 판보다 앞서 있어도 질문 목록은 지금 판의 SHA 와 그 판의 글 해시를 낸다`() {
        workingTreeAheadOfHead(QuizContent.OLD_PROCESS_QUESTIONS, QuizContent.OLD_PROCESS_ANSWERS)

        val response = bed.questions(TOPIC)

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(QuizContent.HEAD, data["content_sha"])
        @Suppress("UNCHECKED_CAST")
        val questions = data["questions"] as List<Map<String, Any?>>
        val expected = splitAt(QuizContent.HEAD).questions
        assertEquals(expected.map { it.text }, questions.map { it["text"] })
        assertEquals(expected.map { it.hash }, questions.map { it["hash"] })
        assertFalse(response.contentAsString.contains("(옛 판)"), "작업 트리(새 판)의 글이 섞였다")
    }

    @Test
    fun `작업 트리가 지금 판보다 앞서 있어도 정답 파일만 바뀐 경우 저장된 판과 응답의 정답이 맞고 재전송도 같은 정답을 준다`() {
        val (token, _) = bed.knownVisitor()
        workingTreeAheadOfHead(QuizContent.PROCESS_QUESTIONS,
            QuizContent.PROCESS_ANSWERS.replace("ANSWER-MARK-2 스레드는 주소 공간을 함께 쓴다.", "ANSWER-MARK-SYNCED-2 동기화된 정답."))

        val first = bed.submit(body(), bed.visitorCookie(token))   // 받은 SHA = HEAD, 해시 = HEAD 판의 질문 2

        assertEquals(200, first.status, first.contentAsString)
        assertEquals(true, bed.data(first)["saved"])
        assertEquals(QuizContent.HEAD, bed.fake.attempts.values.single().contentSha)
        assertConsistentWithStoredVersion(first)
        assertFalse(first.contentAsString.contains("ANSWER-MARK-SYNCED-2"), "작업 트리(새 판)의 정답이 실렸다")
        val again = bed.submit(body(), bed.visitorCookie(token))
        assertEquals(correctAnswerOf(first), correctAnswerOf(again))
    }

    @Test
    fun `작업 트리가 지금 판보다 앞서 있어도 받은 판의 질문과 맞는 답은 그 판으로 저장되고 그 판의 정답이 실린다`() {
        val (token, _) = bed.knownVisitor()
        workingTreeAheadOfHead(QuizContent.OLD_PROCESS_QUESTIONS, QuizContent.OLD_PROCESS_ANSWERS)

        val response = bed.submit(body(), bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, bed.data(response)["saved"])
        assertEquals(QuizContent.HEAD, bed.fake.attempts.values.single().contentSha)
        assertConsistentWithStoredVersion(response)
        assertFalse(response.contentAsString.contains("ANSWER-MARK-OLD-2"))
    }

    @Test
    fun `작업 트리가 지금 판보다 앞서 있을 때 받은 판에 없는 해시를 붙여 내면 question_changed 이고 저장되지 않는다`() {
        val (token, _) = bed.knownVisitor()
        workingTreeAheadOfHead(QuizContent.OLD_PROCESS_QUESTIONS, QuizContent.OLD_PROCESS_ANSWERS)

        // 받은 SHA 는 HEAD 인데 해시는 작업 트리(새 판)의 질문 2
        val response = bed.submit(body().plus("question_hash" to QuizContent.oldQuestionTwoHash), bed.visitorCookie(token))

        assertEquals(409, response.status, response.contentAsString)
        assertEquals("question_changed", bed.errorCode(response))
        assertEquals(0, bed.fake.attempts.size, "받은 판에 없는 해시로 저장했다: ${bed.fake.attempts.values}")
        assertNoAnswerText(response.contentAsString)
    }
}
