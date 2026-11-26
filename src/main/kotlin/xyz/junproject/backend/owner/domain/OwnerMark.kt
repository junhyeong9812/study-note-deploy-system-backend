package xyz.junproject.backend.owner.domain

import java.time.Duration
import java.time.Instant

/** 주인이 아닌 까닭 — 로그에는 [word] 만 싣는다 */
enum class OwnerReason(val word: String) {
    NO_SECRET("no_secret"), NO_COOKIE("no_cookie"), MALFORMED("malformed"), BAD_SIGNATURE("bad_signature"), EXPIRED("expired"),
}

/** 판정 — 주인이면 [reason] 은 null */
data class OwnerVerdict(val owner: Boolean, val reason: OwnerReason?)

/**
 * 주인 표시(쿠키 `study_owner` 의 값) — `ow1.<만든 시각 ms>.<서명>` (명세 §17). 어드민(TypeScript)이 같은 값을 만든다.
 * - 서명 = HMAC-SHA256(열쇠 = OWNER_COOKIE_SECRET 의 UTF-8 바이트, 줄 = `study-owner` LF `ow1` LF `<만든 시각 ms 의 10진 글자>` 의 UTF-8 바이트),
 *   base64url 채움 없음 43글자. LF 는 0x0A 하나, 끝에 LF 없음.
 * - 만든 시각은 앞 0 없는 10진(1~16자리).
 */
object OwnerMark {
    const val VERSION = "ow1"
    const val PURPOSE = "study-owner"
    /** 열쇠의 최소 바이트 수 — 짧거나 비었거나 공백뿐이면 누구도 주인이 아니다 */
    const val MIN_SECRET_BYTES = 32
    /** 수명 — 만든 시각에서 정확히 30일까지 받는다 */
    val LIFETIME: Duration = Duration.ofDays(30)
    /** 만든 시각이 지금보다 이만큼까지 앞서도 받는다(어드민과 backend 의 시계 차) — 넘으면 malformed */
    val FUTURE_TOLERANCE: Duration = Duration.ofSeconds(60)

    /** [createdAtMillis] 에 만든 표시의 서명 — base64url 43글자 */
    fun signature(secret: String, createdAtMillis: Long): String = TODO("2a")

    /** [createdAtMillis] 에 만든 표시의 쿠키 값 */
    fun value(secret: String, createdAtMillis: Long): String = TODO("2a")

    /** 쓸 수 있는 열쇠인가 — 공백뿐이 아니고 UTF-8 로 [MIN_SECRET_BYTES] 바이트 이상 */
    fun usableSecret(secret: String?): Boolean = TODO("2a")

    /**
     * 요청이 보낸 `study_owner` 쿠키의 값들(없으면 빈 목록)로 판정한다. 차례: 열쇠(no_secret) → 쿠키 없음(no_cookie) →
     * 둘 이상 · 꼴(malformed) → 서명(bad_signature, 상수 시간 비교) → 미래 시각(malformed) → 수명(expired)
     */
    fun verify(cookieValues: List<String>, secret: String?, now: Instant): OwnerVerdict = TODO("2a")
}
