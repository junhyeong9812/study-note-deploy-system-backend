package xyz.junproject.backend.owner.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 주인 표시의 값 `ow1.<만든 시각 ms>.<서명>` 과 판정(명세 §17).
 * 서명하는 바이트(어드민이 바이트까지 같게 만든다): UTF-8 `study-owner` + 0x0A + `ow1` + 0x0A + `<만든 시각 ms 의 10진 글자>` (끝에 줄바꿈 없음),
 * 열쇠 = OWNER_COOKIE_SECRET 의 UTF-8 바이트, HMAC-SHA256, base64url 채움 없음 43글자.
 * 아래 벡터는 openssl 로 따로 계산해 맞춰 본 값이다:
 *   printf 'study-owner\now1\n1764000000000' | openssl dgst -sha256 -hmac '<열쇠>' -binary | base64 | tr '+/' '-_' | tr -d '='
 */
class OwnerMarkTest {
    private val asciiSecret = "0123456789abcdef0123456789abcdef"          // 32바이트
    private val koreanSecret = "주인-표시-열쇠-테스트용-가짜-값-0001"          // 49바이트(UTF-8)
    private val otherSecret = "fedcba9876543210fedcba9876543210"

    private val made = 1_764_000_000_000L
    private val madeAt: Instant = Instant.ofEpochMilli(made)
    private val validValue = "ow1.1764000000000.SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54"

    private fun verify(values: List<String>, secret: String? = asciiSecret, now: Instant = madeAt.plusSeconds(3600)) =
        OwnerMark.verify(values, secret, now)

    private fun reasonOf(values: List<String>, secret: String? = asciiSecret, now: Instant = madeAt.plusSeconds(3600)) =
        verify(values, secret, now).let { verdict -> assertFalse(verdict.owner, "주인으로 판정했다: $values"); verdict.reason }

    /** 같은 열쇠로 다른 줄에 서명한 값 — 이 테스트가 직접 HMAC 을 계산한다 */
    private fun hmac(secret: String, line: String): String {
        val mac = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(line.toByteArray(Charsets.UTF_8)))
    }

    // ---- 서명의 바이트 — 어드민과 같은 값 ----

    @Test
    fun `서명과 쿠키 값은 정해진 벡터와 같다`() {
        assertEquals("SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54", OwnerMark.signature(asciiSecret, 1_764_000_000_000L))
        assertEquals("ow1.1764000000000.SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54", OwnerMark.value(asciiSecret, 1_764_000_000_000L))
        assertEquals("ow1.1767225600000.sN1bOvnOjkw-1Lzl0q8JZyAy4DOgxdihMeFWsU-ZkUU", OwnerMark.value(asciiSecret, 1_767_225_600_000L))
        assertEquals("ow1.1._8DX2XdHgwNIJM_xiXI2zViKyiCJhtQzYppQC3l_JEs", OwnerMark.value(asciiSecret, 1L))
        assertEquals("ow1.1764000000000.aDTdYf2XgdlZ_ST20FLwk-h6y6b7mS_JJyFiYHvpB-Q", OwnerMark.value(koreanSecret, 1_764_000_000_000L))
        assertEquals("ow1.1767225600000.egOTJ4l1qLnVp0aOfT5Sm7V8i6pqI7BCXboyDkWLEus", OwnerMark.value(koreanSecret, 1_767_225_600_000L))
    }

    @Test
    fun `서명하는 줄은 용도와 판과 만든 시각을 LF 로 이은 것이다`() {
        assertEquals(hmac(asciiSecret, "study-owner\now1\n1764000000000"), OwnerMark.signature(asciiSecret, made))
    }

    // ---- 통과 ----

    @Test
    fun `바르게 서명한 30일 안의 표시는 주인이다`() {
        assertEquals(OwnerVerdict(owner = true, reason = null), verify(listOf(validValue)))
        assertEquals(OwnerVerdict(owner = true, reason = null), verify(listOf(validValue), now = madeAt))
        assertEquals(OwnerVerdict(owner = true, reason = null), verify(listOf(OwnerMark.value(koreanSecret, made)), secret = koreanSecret))
    }

    @Test
    fun `수명은 만든 시각에서 정확히 30일까지이고 1ms 뒤에는 만료다`() {
        val limit = madeAt.plus(Duration.ofDays(30))
        assertTrue(verify(listOf(validValue), now = limit.minusMillis(1)).owner)
        assertTrue(verify(listOf(validValue), now = limit).owner)
        assertEquals(OwnerReason.EXPIRED, reasonOf(listOf(validValue), now = limit.plusMillis(1)))
    }

    @Test
    fun `만든 시각이 지금보다 60초까지 앞선 것은 받고 넘으면 꼴이 틀린 것이다`() {
        assertTrue(verify(listOf(validValue), now = madeAt.minusSeconds(60)).owner)
        assertEquals(OwnerReason.MALFORMED, reasonOf(listOf(validValue), now = madeAt.minusSeconds(60).minusMillis(1)))
    }

    // ---- 거절의 까닭 ----

    @Test
    fun `쿠키가 없으면 no_cookie 다`() {
        assertEquals(OwnerReason.NO_COOKIE, reasonOf(emptyList()))
    }

    @Test
    fun `꼴이 틀린 값은 malformed 다`() {
        val signature = OwnerMark.signature(asciiSecret, made)
        val malformed = listOf(
            "", "ow1", "ow1.$made", "ow1.$made.$signature.extra", "ow1..$signature", ".$made.$signature",
            "ow2.$made.${hmac(asciiSecret, "study-owner\now2\n$made")}", "OW1.$made.$signature",
            "ow1.abc.$signature", "ow1.0$made.${hmac(asciiSecret, "study-owner\now1\n0$made")}", "ow1.-$made.$signature",
            "ow1.+$made.$signature", "ow1.${made}0000.$signature", "ow1.1${"0".repeat(16)}.$signature", "ow1. $made.$signature",
            "ow1.$made.$signature=", "ow1.$made.${signature.dropLast(1)}", "ow1.$made.${signature}A", "ow1.$made.${signature.dropLast(1)}+",
            "ow1.$made.${signature.dropLast(1)}/", " $validValue", "$validValue ", validValue + "x".repeat(200),
        )
        malformed.forEach { value ->
            assertEquals(OwnerReason.MALFORMED, reasonOf(listOf(value)), "꼴이 틀린 값($value)의 까닭")
        }
        // 앞 0 · 음수는 서명이 맞아도 꼴에서 거절한다
        assertEquals(OwnerReason.MALFORMED, reasonOf(listOf("ow1.0$made.${hmac(asciiSecret, "study-owner\now1\n0$made")}")))
        assertEquals(OwnerReason.MALFORMED, reasonOf(listOf("ow1.-1.${hmac(asciiSecret, "study-owner\now1\n-1")}")))
        assertEquals(OwnerReason.MALFORMED, reasonOf(listOf("ow1.0.${hmac(asciiSecret, "study-owner\now1\n0")}")))
    }

    @Test
    fun `쿠키가 둘이면 둘 다 바르게 서명했어도 malformed 다`() {
        assertEquals(OwnerReason.MALFORMED, reasonOf(listOf(validValue, validValue)))
        assertEquals(OwnerReason.MALFORMED, reasonOf(listOf(validValue, "ow1.1.x")))
    }

    @Test
    fun `서명이 한 글자라도 다르면 bad_signature 다`() {
        val signature = validValue.substringAfterLast('.')
        signature.indices.forEach { index ->
            val changed = signature.toCharArray().also { characters -> characters[index] = if (characters[index] == 'A') 'B' else 'A' }.concatToString()
            assertEquals(OwnerReason.BAD_SIGNATURE, reasonOf(listOf("ow1.$made.$changed")), "서명의 ${index}번째 글자를 바꿨다")
        }
    }

    @Test
    fun `마지막 글자의 남는 비트만 다른 서명도 받지 않는다`() {
        // 43글자의 마지막 글자는 위 4비트만 쓴다 — 아래 2비트가 다른 글자는 풀면 같은 바이트가 된다. 글자 그대로 견준다
        val signature = validValue.substringAfterLast('.')
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val last = alphabet.indexOf(signature.last())
        val sameTopBits = alphabet[(last and 0b111100) or ((last + 1) and 0b11)]
        assertNotEquals(signature.last(), sameTopBits)
        val verdict = verify(listOf("ow1.$made.${signature.dropLast(1)}$sameTopBits"))
        assertFalse(verdict.owner, "아래 비트만 다른 서명을 받았다")
        assertTrue(verdict.reason == OwnerReason.BAD_SIGNATURE || verdict.reason == OwnerReason.MALFORMED, verdict.toString())
    }

    @Test
    fun `다른 열쇠로 서명한 것과 같은 열쇠로 다른 줄에 서명한 것은 bad_signature 다`() {
        assertEquals(OwnerReason.BAD_SIGNATURE, reasonOf(listOf(OwnerMark.value(otherSecret, made))))
        // 열쇠를 바꾸면 옛 표시는 모두 무효
        assertEquals(OwnerReason.BAD_SIGNATURE, reasonOf(listOf(validValue), secret = otherSecret))
        listOf(
            "study-visitor\now1\n$made", "study-owner\now1\n$made\n", "study-owner.ow1.$made", "ow1\n$made", "$made",
            "study-owner\now1\n${made + 1}", "study-owner\r\now1\r\n$made",
        ).forEach { line ->
            assertEquals(OwnerReason.BAD_SIGNATURE, reasonOf(listOf("ow1.$made.${hmac(asciiSecret, line)}")), "다른 줄(${line.replace("\n", "\\n")})의 서명을 받았다")
        }
    }

    @Test
    fun `서명이 틀린 만료된 표시는 만료가 아니라 bad_signature 다`() {
        val expiredForged = "ow1.1.${OwnerMark.signature(otherSecret, 1L)}"
        assertEquals(OwnerReason.BAD_SIGNATURE, reasonOf(listOf(expiredForged)))
        assertEquals(OwnerReason.EXPIRED, reasonOf(listOf(OwnerMark.value(asciiSecret, 1L))))
    }

    // ---- 열쇠 ----

    @Test
    fun `열쇠가 없거나 비었거나 공백뿐이거나 32바이트보다 짧으면 누구도 주인이 아니다`() {
        val short = "0123456789abcdef0123456789abcde"   // 31바이트
        listOf(null, "", " ".repeat(40), short).forEach { secret ->
            assertFalse(OwnerMark.usableSecret(secret), "쓸 수 없는 열쇠를 받았다: ${secret?.length}")
        }
        // 그 열쇠로 바르게 서명한 표시 — 빈 열쇠 · 짧은 열쇠로 서명한 것도 — 를 받지 않는다
        assertEquals(OwnerReason.NO_SECRET, reasonOf(listOf(validValue), secret = null))
        // 빈 열쇠의 HMAC(정의대로 0 바이트 열쇠 — 파이썬 hmac 으로 계산한 값)
        assertEquals(OwnerReason.NO_SECRET, reasonOf(listOf("ow1.$made.aWNL5O3Oe6eOnc0_o5bkQB8K1DunxCqPP08-wsTzl6w"), secret = ""))
        assertEquals(OwnerReason.NO_SECRET, reasonOf(listOf("ow1.$made.${hmac(short, "study-owner\now1\n$made")}"), secret = short))
        assertEquals(OwnerReason.NO_SECRET, reasonOf(emptyList(), secret = null), "열쇠가 없으면 쿠키가 없어도 까닭은 no_secret 이다")
    }

    @Test
    fun `열쇠는 UTF-8 바이트로 32바이트 이상이면 쓴다`() {
        assertTrue(OwnerMark.usableSecret(asciiSecret))
        assertTrue(OwnerMark.usableSecret("가".repeat(11)), "33바이트(11글자)")
        assertFalse(OwnerMark.usableSecret("가".repeat(10)), "30바이트(10글자)")
    }
}
