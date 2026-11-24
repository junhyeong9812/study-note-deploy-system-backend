package xyz.junproject.backend.progress.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 방문자 토큰 — 로그인 없이 브라우저를 알아보는 값(명세 2.1).
 * 원문은 브라우저의 쿠키에만 있고 서버에는 [hash] 만 둔다.
 */
object VisitorToken {
    private const val RANDOM_BYTES = 32
    private val wellFormed = Regex("^[A-Za-z0-9_-]{43}$")

    /** 난수 32바이트(256비트)를 base64url(채움 없음)로 — 43글자 */
    fun issue(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 서버가 발급한 꼴인가(43글자의 base64url) — 꼴이 틀린 값은 저장소에 묻지 않는다 */
    fun isWellFormed(token: String): Boolean = wellFormed.matches(token)

    /** 저장소에 두는 값 — UTF-8 바이트의 SHA-256, 소문자 16진수 64글자 */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
