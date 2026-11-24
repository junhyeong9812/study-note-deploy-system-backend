package xyz.junproject.backend.progress.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/** 명세 2.1 — 추측할 수 없는 난수(256비트 이상) · 서버에는 해시만 · 서버가 발급하지 않은 꼴은 받지 않는다 */
class VisitorTokenTest {
    private fun sha256Hex(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `발급한 토큰은 난수 256비트를 base64url 43글자로 쓴 것이다`() {
        val token = VisitorToken.issue()
        assertEquals(43, token.length, token)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(token), token)
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
    }

    @Test
    fun `발급할 때마다 다른 값이 나온다`() {
        val issued = (1..1_000).map { VisitorToken.issue() }
        assertEquals(1_000, issued.toSet().size)
    }

    @Test
    fun `발급한 꼴만 받아들이고 꼴이 틀린 값은 거절한다`() {
        assertTrue(VisitorToken.isWellFormed(VisitorToken.issue()))
        val wellFormed = "A".repeat(42) + "_"
        assertTrue(VisitorToken.isWellFormed(wellFormed))
        val malformed = listOf(
            "",
            "A".repeat(42),                           // 짧음
            "A".repeat(44),                           // 김
            "A".repeat(42) + "+",                     // base64url 이 아닌 글자
            "A".repeat(42) + "=",
            "A".repeat(42) + ".",
            "A".repeat(41) + " A",
            "123e4567-e89b-12d3-a456-426614174000",   // chat_session 의 꼴
        )
        malformed.forEach { assertFalse(VisitorToken.isWellFormed(it), "받아들이면 안 되는 값: '$it'") }
    }

    @Test
    fun `서버에 두는 값은 토큰의 SHA-256 16진수이고 원문을 담지 않는다`() {
        val token = VisitorToken.issue()
        val hash = VisitorToken.hash(token)
        assertEquals(sha256Hex(token), hash)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash), hash)
        assertFalse(hash.contains(token))
        assertEquals(hash, VisitorToken.hash(token))
        assertNotEquals(hash, VisitorToken.hash(VisitorToken.issue()))
    }
}
