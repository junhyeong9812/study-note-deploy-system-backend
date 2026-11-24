package xyz.junproject.backend.progress.api

import org.springframework.http.ResponseCookie

/** 방문자 토큰 쿠키 `study_visitor` — HttpOnly · Secure · SameSite=Lax · Path=/ · 1년(명세 2.1) */
object VisitorCookie {
    const val NAME = "study_visitor"

    /** 발급 · 기한 늘리기 */
    fun issue(token: String): ResponseCookie = TODO("task 02")

    /** 내 기록 지우기 — 쿠키를 없앤다 */
    fun expire(): ResponseCookie = TODO("task 02")
}
