package xyz.junproject.backend.chat.api

import org.springframework.http.ResponseCookie
import java.time.Duration

/** 대화 세션 쿠키 `chat_session` — HttpOnly · Secure · SameSite=Lax · Path=/ · 30일(명세 2.1). 값의 꼴은 ChatService.isSessionId */
object ChatSessionCookie {
    const val NAME = "chat_session"
    private val LIFETIME: Duration = Duration.ofDays(30)

    private fun cookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAge).build()

    fun issue(sessionId: String): ResponseCookie = cookie(sessionId, LIFETIME)

    /** 내 기록 지우기 — 대화 이력을 지운 뒤 쿠키를 없앤다 */
    fun expire(): ResponseCookie = cookie("", Duration.ZERO)
}
