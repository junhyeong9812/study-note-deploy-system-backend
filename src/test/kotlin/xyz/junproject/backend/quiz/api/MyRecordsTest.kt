package xyz.junproject.backend.quiz.api

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.api.VisitorCookie
import xyz.junproject.backend.quiz.QuizContent.OTHER_TOPIC
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.QuizTestBed.Companion.CHAT_COOKIE

/**
 * 명세 18.1 · 2.2 · 12 — 지워질 수 `GET /api/quiz/my-records` · 내 기록 지우기 `DELETE /api/quiz/my-records`.
 * 퀴즈 기록(방문자 줄째)과 대화 이력(`chat_session` 의 Redis 기록)을 지우고 두 쿠키를 만료시킨다.
 * 한쪽만 실패하면 그렇다고 답한다(무엇이 지워졌고 무엇이 안 지워졌는가) — 안 지워진 쪽의 쿠키는 만료시키지 않는다. 남의 기록은 그대로다.
 */
class MyRecordsTest {
    private val bed = QuizTestBed()
    private val session = "123e4567-e89b-12d3-a456-426614174000"
    private val otherSession = "00000000-1111-2222-3333-444444444444"

    @AfterEach
    fun close() = bed.close()

    private fun chatCookie(value: String = session) = Cookie(CHAT_COOKIE, value)

    /** 방문자 하나와 시도 셋, 대화 이력이 있는 세션 */
    private fun visitorWithRecords(): String {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, QuizTestBed.NOW)
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        bed.putAttempt(visitor, OTHER_TOPIC, 1, QuizTestBed.NOW)
        bed.chatHistory.sessionsWithHistory += session
        return token
    }

    private fun assertExpired(setCookieLine: String?, name: String) {
        assertNotNull(setCookieLine, "$name 를 만료시키지 않았다")
        assertTrue(setCookieLine!!.startsWith("$name=;") || setCookieLine.startsWith("$name=\"\";"), setCookieLine)
        val attributes = bed.attributesOf(setCookieLine)
        assertEquals("0", attributes["max-age"], setCookieLine)
        assertEquals("/", attributes["path"], setCookieLine)
    }

    // ── 지워질 수 ──────────────────────────────────────────

    @Test
    fun `지워질 수는 퀴즈 시도의 수와 대화 이력의 유무이고 아무것도 지우지 않으며 쿠키를 놓지 않는다`() {
        val token = visitorWithRecords()
        val response = bed.myRecords(bed.visitorCookie(token), chatCookie())
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(mapOf("quiz_attempts" to 3, "chat_history" to true), bed.data(response))
        assertEquals(3, bed.fake.attempts.size)
        assertEquals(setOf(session), bed.chatHistory.sessionsWithHistory)
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `대화 이력이 없는 세션이면 chat_history 는 false 다`() {
        val token = visitorWithRecords()
        val data = bed.data(bed.myRecords(bed.visitorCookie(token), chatCookie(otherSession)))
        assertEquals(false, data["chat_history"])
    }

    @Test
    fun `쿠키가 없으면 0 과 false 이고 저장소와 Redis 에 닿지 않는다`() {
        val response = bed.myRecords()
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(mapOf("quiz_attempts" to 0, "chat_history" to false), bed.data(response))
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(emptyList<Pair<String, String>>(), bed.chatHistory.calls)
    }

    @Test
    fun `확인할 수 없는 쪽은 null 로 답한다 - 조용히 0 이나 false 로 보이지 않는다`() {
        val token = visitorWithRecords()
        bed.fake.failing += "countAttempts"
        assertEquals(mapOf("quiz_attempts" to null, "chat_history" to true),
            bed.data(bed.myRecords(bed.visitorCookie(token), chatCookie())))

        bed.fake.failing.clear()
        bed.chatHistory.failing += "hasHistory"
        val response = bed.myRecords(bed.visitorCookie(token), chatCookie())
        assertEquals(200, response.status)
        assertEquals(mapOf("quiz_attempts" to 3, "chat_history" to null), bed.data(response))
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    // ── 지우기 ────────────────────────────────────────────

    @Test
    fun `지우면 방문자 줄째 지워지고 대화 이력도 지워지며 두 쿠키가 만료된다`() {
        val token = visitorWithRecords()
        val response = bed.deleteMyRecords(bed.visitorCookie(token), chatCookie())

        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, bed.body(response)["success"])
        assertEquals(mapOf("quiz_records" to "deleted", "deleted_attempts" to 3, "chat_history" to "deleted"), bed.data(response))
        assertEquals(0, bed.fake.visitorsByHash.size)
        assertEquals(0, bed.fake.attempts.size)
        assertEquals(listOf("deleteHistory" to session), bed.chatHistory.calls.filter { it.first == "deleteHistory" })
        assertEquals(emptySet<String>(), bed.chatHistory.sessionsWithHistory)
        val cookies = bed.setCookies(response)
        bed.assertSameCookie(VisitorCookie.expire(), cookies[VisitorCookie.NAME])
        assertExpired(cookies[CHAT_COOKIE], CHAT_COOKIE)
    }

    @Test
    fun `퀴즈 기록은 지워졌는데 대화 이력이 실패하면 그렇다고 답하고 chat_session 은 만료시키지 않는다`() {
        val token = visitorWithRecords()
        bed.chatHistory.failing += "deleteHistory"
        val response = bed.deleteMyRecords(bed.visitorCookie(token), chatCookie())

        assertEquals(503, response.status, response.contentAsString)
        assertEquals(false, bed.body(response)["success"])
        assertEquals("not_fully_deleted", bed.errorCode(response))
        assertEquals(mapOf("quiz_records" to "deleted", "deleted_attempts" to 3, "chat_history" to "failed"), bed.data(response))
        val cookies = bed.setCookies(response)
        bed.assertSameCookie(VisitorCookie.expire(), cookies[VisitorCookie.NAME])
        assertFalse(CHAT_COOKIE in cookies, cookies.toString())
        assertEquals(0, bed.fake.visitorsByHash.size)
    }

    @Test
    fun `대화 이력은 지워졌는데 퀴즈 기록이 실패하면 그렇다고 답하고 study_visitor 는 만료시키지 않는다`() {
        val token = visitorWithRecords()
        bed.fake.failing += "deleteVisitor"
        val response = bed.deleteMyRecords(bed.visitorCookie(token), chatCookie())

        assertEquals(503, response.status, response.contentAsString)
        assertEquals(false, bed.body(response)["success"])
        assertEquals("not_fully_deleted", bed.errorCode(response))
        assertEquals(mapOf("quiz_records" to "failed", "deleted_attempts" to null, "chat_history" to "deleted"), bed.data(response))
        val cookies = bed.setCookies(response)
        assertFalse(VisitorCookie.NAME in cookies, cookies.toString())
        assertExpired(cookies[CHAT_COOKIE], CHAT_COOKIE)
        assertEquals(1, bed.fake.visitorsByHash.size)
        assertEquals(3, bed.fake.attempts.size)
    }

    @Test
    fun `방문자를 확인할 수 없어도 퀴즈 기록은 실패로 답하고 study_visitor 를 건드리지 않는다`() {
        val token = visitorWithRecords()
        bed.fake.failing += "findVisitor"
        bed.chatHistory.failing += "deleteHistory"
        val response = bed.deleteMyRecords(bed.visitorCookie(token), chatCookie())
        assertEquals(503, response.status, response.contentAsString)
        assertEquals(mapOf("quiz_records" to "failed", "deleted_attempts" to null, "chat_history" to "failed"), bed.data(response))
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun `study_visitor 만 있으면 퀴즈 기록만 지우고 대화 이력에는 닿지 않는다`() {
        val token = visitorWithRecords()
        val response = bed.deleteMyRecords(bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(mapOf("quiz_records" to "deleted", "deleted_attempts" to 3, "chat_history" to "none"), bed.data(response))
        assertEquals(emptyList<Pair<String, String>>(), bed.chatHistory.calls)
        assertEquals(setOf(session), bed.chatHistory.sessionsWithHistory)
        bed.assertSameCookie(VisitorCookie.expire(), bed.setCookies(response)[VisitorCookie.NAME])
    }

    @Test
    fun `chat_session 만 있으면 대화 이력만 지우고 퀴즈 저장소에는 닿지 않는다`() {
        visitorWithRecords()
        val response = bed.deleteMyRecords(chatCookie())
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(mapOf("quiz_records" to "none", "deleted_attempts" to 0, "chat_history" to "deleted"), bed.data(response))
        assertEquals(emptyList<String>(), bed.fake.calls)
        assertEquals(3, bed.fake.attempts.size)
        assertExpired(bed.setCookies(response)[CHAT_COOKIE], CHAT_COOKIE)
    }

    @Test
    fun `남의 기록은 그대로다 - 지어낸 토큰과 다른 세션으로 지워도 주인의 시도와 대화 이력이 남는다`() {
        visitorWithRecords()
        bed.chatHistory.sessionsWithHistory += otherSession
        val (otherToken, otherVisitor) = bed.knownVisitor()
        bed.putAttempt(otherVisitor, TOPIC, 3, QuizTestBed.NOW)

        val response = bed.deleteMyRecords(bed.visitorCookie(otherToken), chatCookie(otherSession))
        assertEquals(200, response.status, response.contentAsString)
        assertEquals(1, bed.data(response)["deleted_attempts"])
        assertEquals(3, bed.fake.attempts.size, "주인의 시도가 지워졌다")
        assertEquals(1, bed.fake.visitorsByHash.size)
        assertEquals(setOf(session), bed.chatHistory.sessionsWithHistory)

        val forged = bed.deleteMyRecords(bed.visitorCookie("Z".repeat(43)))
        assertEquals(200, forged.status)
        assertEquals(3, bed.fake.attempts.size, "지어낸 토큰으로 남의 시도가 지워졌다")
    }

    @Test
    fun `꼴이 틀린 chat_session 값으로는 대화 이력에 닿지 않는다`() {
        visitorWithRecords()
        listOf("*", "chat:*", "$session*", "not-a-session").forEach { forged ->
            val count = bed.myRecords(chatCookie(forged))
            assertEquals(false, bed.data(count)["chat_history"], forged)
            val response = bed.deleteMyRecords(chatCookie(forged))
            assertEquals("none", bed.data(response)["chat_history"], forged)
        }
        assertEquals(emptyList<Pair<String, String>>(), bed.chatHistory.calls)
        assertEquals(setOf(session), bed.chatHistory.sessionsWithHistory)
    }
}
