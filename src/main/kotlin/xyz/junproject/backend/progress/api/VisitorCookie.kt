package xyz.junproject.backend.progress.api

import org.springframework.http.ResponseCookie
import java.time.Duration

/** 방문자 토큰 쿠키 `study_visitor` — HttpOnly · Secure · SameSite=Lax · Path=/ · 1년(명세 2.1) */
object VisitorCookie {
    const val NAME = "study_visitor"
    private val LIFETIME: Duration = Duration.ofDays(365)

    private fun cookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAge).build()

    /** 발급 · 기한 늘리기 */
    fun issue(token: String): ResponseCookie = cookie(token, LIFETIME)

    /** 내 기록 지우기 — 쿠키를 없앤다 */
    fun expire(): ResponseCookie = cookie("", Duration.ZERO)
}
