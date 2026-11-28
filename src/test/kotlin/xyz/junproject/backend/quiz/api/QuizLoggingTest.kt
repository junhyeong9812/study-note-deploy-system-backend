package xyz.junproject.backend.quiz.api

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.api.VisitorCookie
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed

/**
 * 명세 18.2 · 11.2 · 2.1 — 로그에 쓴 답의 본문 · 토큰 원문 · 정답 글을 쓰지 않는다. 주제 경로는 검증을 통과한 것만 쓴다.
 * 운영의 로그 단계(INFO 이상)만 본다 — 시험대의 MockMvc 가 DEBUG 로 요청 · 응답 본문을 남기는 것은 운영에서 나오지 않는다.
 */
class QuizLoggingTest {
    private val bed = QuizTestBed()
    private val secretAnswer = "MY-SECRET-ANSWER-쓴답본문"
    private val session = "123e4567-e89b-12d3-a456-426614174000"

    @AfterEach
    fun close() = bed.close()

    private fun productionLines() = bed.logs.lines.filterNot { it.startsWith("DEBUG") || it.startsWith("TRACE") }

    @Test
    fun `쓴 답의 본문 토큰 원문 정답 글이 어느 로거에도 없다 - 성공과 실패의 경로 모두`() {
        // 첫 답 → 쿠키
        val first = bed.submit(bed.answerBody(answer = secretAnswer))
        val token = bed.issuedTokens.single()
        val cookie = bed.visitorCookie(token)
        val attemptId = (bed.data(first)["attempt"] as Map<*, *>)["attempt_id"]
        // 아는 방문자의 답 · 같은 번호의 재전송 · 채점 · 질문 목록 · 다시 보기 · 복습 · 지워질 수
        val again = bed.answerBody(answer = "$secretAnswer-둘째")
        bed.submit(again, cookie)
        bed.submit(again, cookie)
        bed.grade(mapOf("attempt_id" to attemptId, "grade" to "WRONG"), cookie)
        bed.questions(TOPIC, cookie)
        bed.revisit(TOPIC, "2", cookie)
        bed.review(cookie)
        bed.myRecords(cookie, Cookie(QuizTestBed.CHAT_COOKIE, session))
        // 거절 · 실패의 경로
        bed.submit(bed.answerBody(answer = "$secretAnswer-" + "가".repeat(4_001)), cookie)
        bed.submit(bed.answerBody(answer = "$secretAnswer\u0000"), cookie)
        bed.submit(bed.answerBody(answer = "$secretAnswer-바뀐질문", questionHash = QuizContent.UNKNOWN_HASH), cookie)
        bed.submit(bed.answerBody(answer = "$secretAnswer-남의번호", attemptId = attemptId), bed.visitorCookie("Z".repeat(43)))
        bed.fake.failing += setOf("recordAttempt", "recordFirstAttempt")
        bed.submit(bed.answerBody(answer = "$secretAnswer-장애"), cookie)
        bed.submit(bed.answerBody(answer = "$secretAnswer-장애-새방문자"))
        bed.fake.failing.clear()
        bed.chatHistory.failing += "deleteHistory"
        bed.deleteMyRecords(cookie, Cookie(QuizTestBed.CHAT_COOKIE, session))

        val lines = productionLines()
        assertTrue(lines.isNotEmpty(), "로그가 하나도 없다 — 잡는 틀이 깨졌다")
        val forbidden = listOf(secretAnswer, token, VisitorCookie.issue(token).value) + QuizContent.ANSWER_MARKERS
        forbidden.forEach { text ->
            val leaked = lines.filter { it.contains(text) }
            assertEquals(emptyList<String>(), leaked, "로그에 [$text] 가 있다")
        }
    }

    @Test
    fun `거절된 주제 경로의 원문은 로그에 쓰이지 않아 로그의 꼴을 깨지 못한다`() {
        val forgedTopics = listOf(
            "$TOPIC\nreq-forged:backend:FORGED-NEWLINE",
            "$TOPIC\rreq-forged:backend:FORGED-RETURN",
            "$TOPIC:FORGED-COLON",
            "cs/../FORGED-TRAVERSAL",
            "languages/FORGED-OUTSIDE",
        )
        forgedTopics.forEach { topic ->
            bed.questions(topic)
            bed.submit(bed.answerBody().plus("topic" to topic))
            bed.revisit(topic, "1")
        }
        val lines = productionLines()
        listOf("FORGED-NEWLINE", "FORGED-RETURN", "FORGED-COLON", "FORGED-TRAVERSAL", "FORGED-OUTSIDE").forEach { marker ->
            assertEquals(emptyList<String>(), lines.filter { it.contains(marker) }, "거절된 주제 경로가 로그에 있다: $marker")
        }
        assertEquals(emptyList<String>(), lines.filter { it.contains('\n') || it.contains('\r') }, "로그 한 줄에 줄바꿈이 있다")
    }
}
