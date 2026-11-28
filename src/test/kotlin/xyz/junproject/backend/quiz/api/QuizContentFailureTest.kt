package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import java.time.Instant
import java.util.UUID

/**
 * 명세 23 — git 의 장애를 "없는 주제"로 답하지 않는다: 판을 지정한 읽기는 git 이 "그 판에 그 파일이 없다 · 그 판이 없다"고 답한 경우만 없음이다.
 * 그 밖의 실패(가짜의 IllegalStateException("git timeout"))는 장애 — 퀴즈 경로는 503 `content_unavailable` 로 답하고 ERROR 로그를 남긴다.
 * 404 · 409 · "퀴즈 없음(정답 모자람)" · `missing_topics` 로 바뀌지 않는다. 정답 파일만 장애여도 같다. 지금 판을 얻는 읽기의 장애도 같다.
 */
class QuizContentFailureTest {
    private val bed = QuizTestBed()
    private val questionFile = "$TOPIC/1-question.md"
    private val answerFile = "$TOPIC/3-answer.md"

    @AfterEach
    fun close() = bed.close()

    private fun productionLines() = bed.logs.lines.filterNot { it.startsWith("DEBUG") || it.startsWith("TRACE") }

    private fun assertContentUnavailable(response: org.springframework.mock.web.MockHttpServletResponse, what: String) {
        assertEquals(503, response.status, "$what: ${response.contentAsString}")
        assertEquals(false, bed.body(response)["success"], what)
        assertEquals("content_unavailable", bed.errorCode(response), what)
        QuizContent.ANSWER_MARKERS.forEach { marker -> assertFalse(response.contentAsString.contains(marker), "$what: ${response.contentAsString}") }
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"), what)
    }

    private fun assertErrorLogged(what: String) {
        val errors = productionLines().filter { it.startsWith("ERROR") }
        assertTrue(errors.isNotEmpty(), "$what: ERROR 로그가 없다 — ${productionLines()}")
    }

    // ── 질문 목록 ──────────────────────────────────────────

    @Test
    fun `질문 파일 읽기가 장애면 질문 목록은 404 가 아니라 503 이고 ERROR 로그가 남는다`() {
        bed.notes.failingPaths += questionFile
        assertContentUnavailable(bed.questions(TOPIC), "질문 파일 장애")
        assertErrorLogged("질문 파일 장애")
    }

    @Test
    fun `정답 파일만 장애여도 퀴즈 없음(정답 모자람)이 아니라 503 이다`() {
        bed.notes.failingPaths += answerFile
        val response = bed.questions(TOPIC)
        assertContentUnavailable(response, "정답 파일 장애")
        assertFalse(response.contentAsString.contains("MISSING_ANSWER"), response.contentAsString)
        assertErrorLogged("정답 파일 장애")
    }

    @Test
    fun `지금 판을 얻는 읽기가 장애면 질문 목록은 500 이 아니라 503 이다`() {
        bed.notes.currentHeadFails = true
        assertContentUnavailable(bed.questions(TOPIC), "지금 판 장애")
        assertErrorLogged("지금 판 장애")
    }

    @Test
    fun `진짜 없는 주제는 지금처럼 404 이다`() {
        val response = bed.questions("cs/os/nothing-here")
        assertEquals(404, response.status)
        assertEquals("not_found", bed.errorCode(response))
    }

    // ── 답을 냄 ────────────────────────────────────────────

    @Test
    fun `답을 낼 때 질문 파일이 장애면 저장하지 않고 503 이며 쿠키를 놓지 않고 ERROR 로그에 답 본문이 없다`() {
        bed.notes.failingPaths += questionFile
        val response = bed.submit(bed.answerBody(answer = "LOGGED-ANSWER-BODY 장애 중에 쓴 답"))
        assertContentUnavailable(response, "답 내기 · 질문 파일 장애")
        assertEquals(emptyList<String>(), bed.fake.calls, "장애인데 저장소에 닿았다")
        assertEquals(0, bed.issuedTokens.size)
        assertErrorLogged("답 내기")
        assertEquals(emptyList<String>(), productionLines().filter { it.contains("LOGGED-ANSWER-BODY") }, "로그에 답 본문이 있다")
    }

    @Test
    fun `답을 낼 때 정답 파일만 장애여도 409 퀴즈 없음이 아니라 503 이다`() {
        bed.notes.failingPaths += answerFile
        assertContentUnavailable(bed.submit(bed.answerBody()), "답 내기 · 정답 파일 장애")
        assertEquals(0, bed.fake.attempts.size)
    }

    @Test
    fun `받은 판의 읽기가 장애면 지금 판으로 넘어가 견주지 않고 503 이다`() {
        bed.notes.failingAt += QuizContent.OLD_SHA to questionFile   // 지금 판은 멀쩡하다 — 넘어가면 해시가 달라 409 가 된다
        assertContentUnavailable(bed.submit(bed.answerBody(contentSha = QuizContent.OLD_SHA, questionHash = QuizContent.oldQuestionTwoHash)),
            "옛 판 장애")
        assertEquals(0, bed.fake.attempts.size)
    }

    @Test
    fun `받은 판을 읽을 수 없고 지금 판을 얻는 읽기가 장애면 503 이다`() {
        bed.notes.currentHeadFails = true
        assertContentUnavailable(bed.submit(bed.answerBody(contentSha = QuizContent.UNREADABLE_SHA)), "지금 판 장애")
        assertEquals(0, bed.fake.attempts.size)
    }

    @Test
    fun `아는 방문자의 답도 내용 장애면 저장하지 않고 쿠키를 놓지 않는다`() {
        val (token, _) = bed.knownVisitor()
        bed.notes.failingPaths += questionFile
        assertContentUnavailable(bed.submit(bed.answerBody(), bed.visitorCookie(token)), "아는 방문자")
        assertEquals(0, bed.fake.attempts.size)
    }

    // ── 재전송 ────────────────────────────────────────────

    @Test
    fun `저장된 자기 시도의 재전송에서 정답을 찾다 장애가 나면 200 저장됨에 정답 칸이 비고 그 사실을 알리며 ERROR 로그가 남는다`() {
        val (token, visitor) = bed.knownVisitor()
        val attemptId = UUID.randomUUID()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW, answer = "LOGGED-ANSWER-BODY 처음 쓴 답", attemptId = attemptId)
        bed.notes.failingPaths += questionFile

        val response = bed.submit(bed.answerBody(number = 2, answer = "LOGGED-ANSWER-BODY 처음 쓴 답", attemptId = attemptId.toString()),
            bed.visitorCookie(token))

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(true, data["saved"])
        assertEquals(attemptId.toString(), (data["attempt"] as Map<*, *>)["attempt_id"])
        assertTrue(data.containsKey("correct_answer"))
        assertNull(data["correct_answer"])
        assertEquals(true, data["correct_answer_unavailable"], response.contentAsString)
        assertErrorLogged("재전송의 정답")
        assertEquals(emptyList<String>(), productionLines().filter { it.contains("LOGGED-ANSWER-BODY") }, "로그에 답 본문이 있다")
        assertEquals(1, bed.fake.attempts.size)
    }

    @Test
    fun `평소의 저장 응답에서 correct_answer_unavailable 은 false 다`() {
        val data = bed.data(bed.submit(bed.answerBody()))
        assertEquals(true, data["saved"])
        assertEquals(false, data["correct_answer_unavailable"])
    }

    // ── 다시 보기 · 복습 목록 ───────────────────────────────

    @Test
    fun `다시 보기에서 내용 장애면 503 이다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        bed.notes.failingPaths += answerFile
        assertContentUnavailable(bed.revisit(TOPIC, "2", bed.visitorCookie(token)), "다시 보기")
        assertErrorLogged("다시 보기")
    }

    @Test
    fun `복습 목록에서 내용 장애를 없어진 주제로 세지 않고 503 이다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        bed.notes.failingPaths += questionFile
        val response = bed.review(bed.visitorCookie(token))
        assertContentUnavailable(response, "복습 목록")
        assertFalse(response.contentAsString.contains("missing_topics"), response.contentAsString)
        assertErrorLogged("복습 목록")
    }

    @Test
    fun `복습 목록에서 지금 판을 얻는 읽기가 장애면 503 이다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        bed.notes.currentHeadFails = true
        assertContentUnavailable(bed.review(bed.visitorCookie(token)), "복습 목록 · 지금 판")
    }
}
