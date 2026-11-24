package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.api.VisitorCookie
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.usecase.QuizService
import java.time.Duration

/**
 * 명세 18.2 — 새 방문자 발급의 속도 제한: 쿠키 없이 내는 첫 답(새 방문자 만들기)은 앱 전체로 분당 정한 수(기본 30)까지.
 * 넘으면 저장하지 않고(방문자 줄 0) `saved: false` · 정답은 싣는다 · 쿠키를 놓지 않는다. 아는 방문자는 걸리지 않고, 지어낸 토큰은 새 방문자로 센다.
 * 시험대의 시작 시각은 분의 시작(01:00:00Z)이다.
 */
class NewVisitorRateLimitTest {
    private val bed = QuizTestBed(newVisitorsPerMinute = 3)

    @AfterEach
    fun close() = bed.close()

    private fun submitWithoutCookie() = bed.submit(bed.answerBody())

    private fun assertLimited(response: org.springframework.mock.web.MockHttpServletResponse) {
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(false, data["saved"])
        assertEquals("new_visitor_rate_limited", data["not_saved_reason"])
        assertEquals(null, data["attempt"])
        assertTrue(response.contentAsString.contains("ANSWER-MARK-2"), "정답이 실리지 않았다: ${response.contentAsString}")
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `쿠키 없는 첫 답이 분당 상한을 넘으면 저장하지 않고 saved false 이며 정답은 싣고 쿠키를 놓지 않는다`() {
        repeat(3) { assertEquals(true, bed.data(submitWithoutCookie())["saved"]) }
        val visitorsBefore = bed.fake.visitorsByHash.size
        val firstAttemptCallsBefore = bed.fake.calls.count { it == "recordFirstAttempt" }

        assertLimited(submitWithoutCookie())

        assertEquals(3, visitorsBefore)
        assertEquals(visitorsBefore, bed.fake.visitorsByHash.size, "방문자 줄이 생겼다")
        assertEquals(firstAttemptCallsBefore, bed.fake.calls.count { it == "recordFirstAttempt" }, "저장소에 첫 답을 넣으려 했다")
        assertEquals(3, bed.issuedTokens.size, "토큰을 발급했다")
    }

    @Test
    fun `분이 지나기 전에는 막혀 있고 시계가 1분을 넘기면 다시 새 방문자를 받는다`() {
        repeat(3) { submitWithoutCookie() }
        bed.clock.advance(Duration.ofSeconds(59))
        assertLimited(submitWithoutCookie())
        bed.clock.advance(Duration.ofSeconds(1))
        val response = submitWithoutCookie()
        assertEquals(true, bed.data(response)["saved"], response.contentAsString)
        assertEquals(4, bed.fake.visitorsByHash.size)
    }

    @Test
    fun `쿠키가 있는 아는 방문자는 새 방문자 상한이 찬 뒤에도 저장된다`() {
        val (token, _) = bed.knownVisitor()
        repeat(3) { submitWithoutCookie() }
        assertLimited(submitWithoutCookie())

        val known = bed.submit(bed.answerBody(), bed.visitorCookie(token))
        assertEquals(true, bed.data(known)["saved"], known.contentAsString)
        bed.assertSameCookie(VisitorCookie.issue(token), bed.setCookies(known)[VisitorCookie.NAME])
    }

    @Test
    fun `아는 방문자의 답은 새 방문자 상한을 쓰지 않는다`() {
        val (token, _) = bed.knownVisitor()
        repeat(5) { assertEquals(true, bed.data(bed.submit(bed.answerBody(), bed.visitorCookie(token)))["saved"]) }
        repeat(3) { assertEquals(true, bed.data(submitWithoutCookie())["saved"]) }
    }

    @Test
    fun `지어낸 토큰은 새 방문자로 센다`() {
        repeat(3) { index ->
            val forged = bed.visitorCookie("Q".repeat(42) + index)
            assertEquals(true, bed.data(bed.submit(bed.answerBody(), forged))["saved"])
        }
        assertLimited(bed.submit(bed.answerBody(), bed.visitorCookie("W".repeat(43))))
        assertLimited(submitWithoutCookie())
    }

    @Test
    fun `기본 상한은 분당 30 이다`() {
        assertEquals(30, QuizService.NEW_VISITORS_PER_MINUTE)
        QuizTestBed().use { defaultBed ->
            repeat(30) { index ->
                val response = defaultBed.submit(defaultBed.answerBody())
                assertEquals(true, defaultBed.data(response)["saved"], "${index + 1}번째: ${response.contentAsString}")
            }
            val over = defaultBed.submit(defaultBed.answerBody())
            assertEquals(false, defaultBed.data(over)["saved"])
            assertEquals("new_visitor_rate_limited", defaultBed.data(over)["not_saved_reason"])
        }
    }
}
