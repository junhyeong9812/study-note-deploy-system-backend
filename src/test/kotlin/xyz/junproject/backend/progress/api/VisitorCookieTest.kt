package xyz.junproject.backend.progress.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

/** 명세 2.1 — 쿠키 `study_visitor`: HttpOnly · Secure · SameSite=Lax · Path=/ · 1년 */
class VisitorCookieTest {
    private val token = "A".repeat(42) + "_"

    @Test
    fun `발급하는 쿠키는 HttpOnly Secure SameSite=Lax Path=루트 1년이다`() {
        val cookie = VisitorCookie.issue(token)
        assertEquals("study_visitor", cookie.name)
        assertEquals(token, cookie.value)
        assertEquals("/", cookie.path)
        assertEquals(Duration.ofDays(365), cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecure)
        assertEquals("Lax", cookie.sameSite)
        assertNull(cookie.domain)
    }

    @Test
    fun `지울 때의 쿠키는 같은 이름과 경로에 기한 0 이다`() {
        val cookie = VisitorCookie.expire()
        assertEquals("study_visitor", cookie.name)
        assertEquals("", cookie.value)
        assertEquals("/", cookie.path)
        assertEquals(Duration.ZERO, cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecure)
        assertEquals("Lax", cookie.sameSite)
    }
}
