package xyz.junproject.backend.progress.domain

import java.security.SecureRandom

/**
 * 방문자 토큰 — 로그인 없이 브라우저를 알아보는 값(명세 2.1).
 * 원문은 브라우저의 쿠키에만 있고 서버에는 [hash] 만 둔다.
 */
object VisitorToken {
    /** 난수 32바이트(256비트)를 base64url(채움 없음)로 — 43글자 */
    fun issue(random: SecureRandom = SecureRandom()): String = TODO("task 02")

    /** 서버가 발급한 꼴인가(43글자의 base64url) — 꼴이 틀린 값은 저장소에 묻지 않는다 */
    fun isWellFormed(token: String): Boolean = TODO("task 02")

    /** 저장소에 두는 값 — UTF-8 바이트의 SHA-256, 소문자 16진수 64글자 */
    fun hash(token: String): String = TODO("task 02")
}
