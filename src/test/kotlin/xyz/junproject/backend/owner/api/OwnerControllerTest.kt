package xyz.junproject.backend.owner.api

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.owner.domain.OwnerMark
import xyz.junproject.backend.owner.usecase.OwnerJudge
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `GET /api/owner` → 봉투 `{success: true, data: {owner: true|false}}` · `Cache-Control: no-store` · `Vary: Cookie` · 주인이 아니어도 200(명세 §17).
 * 판정은 쿠키 `study_owner` 하나로만 — 머리(`X-Owner` 등) · 질의(`owner=true`) · 다른 쿠키(`study_visitor` · `chat_session`)로는 주인이 되지 않는다.
 * 판정은 요청 로그에 `owner=true` · `owner=false reason=<낱말>` 로 남고 쿠키의 값 · 서명은 어디에도 남지 않는다.
 * 열쇠(`OWNER_COOKIE_SECRET`)를 쓸 수 없으면 판정이 만들어질 때 WARN 한 줄이 그 이름을 말한다(값은 싣지 않는다).
 */
class OwnerControllerTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val made = 1_764_000_000_000L
    private val now: Instant = Instant.ofEpochMilli(made).plusSeconds(3600)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ownerValue = "ow1.1764000000000.SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54"
    private val signature = ownerValue.substringAfterLast('.')

    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()

    private val json = JsonMapper.builder().build()

    private fun mockMvc(judgeSecret: String? = secret) = MockMvcBuilders
        .standaloneSetup(OwnerController(OwnerJudge(judgeSecret, clock, logs.requestLog), logs.requestLog))
        .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    private fun perform(request: MockHttpServletRequestBuilder, judgeSecret: String? = secret): MockHttpServletResponse =
        mockMvc(judgeSecret).perform(request).andReturn().response

    private fun ownerOf(response: MockHttpServletResponse): Any? {
        assertEquals(200, response.status, response.contentAsString)
        val body = json.readValue(response.contentAsString, Map::class.java)
        assertEquals(true, body["success"], response.contentAsString)
        val data = body["data"] as Map<*, *>
        assertEquals(setOf("owner"), data.keys, "data 의 칸: ${data.keys}")
        return data["owner"]
    }

    private fun ownerCookie(value: String = ownerValue) = Cookie(OwnerCookie.NAME, value)

    @Test
    fun `바른 표시를 가진 요청은 주인이다`() {
        assertEquals(true, ownerOf(perform(get("/api/owner").cookie(ownerCookie()))))
    }

    @Test
    fun `주인이 아니어도 200 이고 owner 는 거짓이다`() {
        assertEquals(false, ownerOf(perform(get("/api/owner"))))
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie(OwnerMark.value("fedcba9876543210fedcba9876543210", made))))))
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie("ow1.$made.${signature.dropLast(1)}A")))))
    }

    @Test
    fun `응답은 캐시하지 않고 쿠키에 따라 갈린다고 말한다`() {
        listOf(perform(get("/api/owner").cookie(ownerCookie())), perform(get("/api/owner"))).forEach { response ->
            assertEquals("no-store", response.getHeader("Cache-Control"), response.headerNames.toString())
            assertTrue(response.getHeaders("Vary").any { value -> value.split(",").any { it.trim().equals("Cookie", ignoreCase = true) } },
                "Vary 에 Cookie 가 없다: ${response.getHeaders("Vary")}")
            assertNull(response.getHeader("Set-Cookie"), "주인 판정이 쿠키를 심었다")
        }
    }

    @Test
    fun `열쇠를 쓸 수 없으면 바르게 서명한 표시도 주인이 아니다`() {
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie()), judgeSecret = null)))
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie()), judgeSecret = "")))
        val short = "0123456789abcdef0123456789abcde"
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie(OwnerMark.value(short, made))), judgeSecret = short)))
    }

    @Test
    fun `열쇠를 쓸 수 없으면 판정을 만들 때 그 이름을 말하는 WARN 이 남고 값은 남지 않는다`() {
        val short = "short-secret-value-for-test"
        OwnerJudge(short, clock, logs.requestLog)
        OwnerJudge(null, clock, logs.requestLog)
        val warnings = logs.lines.filter { it.startsWith("WARN") && it.contains("OWNER_COOKIE_SECRET") }
        assertEquals(2, warnings.size, "열쇠를 쓸 수 없다는 WARN 이 판정마다 한 줄이 아니다: ${logs.lines}")
        assertTrue(logs.lines.none { it.contains(short) }, "열쇠의 값이 로그에 있다")
    }

    @Test
    fun `쓸 수 있는 열쇠면 그 WARN 이 없다`() {
        OwnerJudge(secret, clock, logs.requestLog)
        assertTrue(logs.lines.none { it.startsWith("WARN") && it.contains("OWNER_COOKIE_SECRET") }, logs.lines.toString())
        assertTrue(logs.lines.none { it.contains(secret) }, "열쇠의 값이 로그에 있다")
    }

    @Test
    fun `머리 질의 다른 쿠키의 글자로는 주인이 되지 않는다`() {
        val attempts = listOf(
            get("/api/owner").header("X-Owner", "true"),
            get("/api/owner").header("X-Owner", ownerValue),
            get("/api/owner").header("X-Study-Owner", ownerValue),
            get("/api/owner").header("Authorization", "Bearer $ownerValue"),
            get("/api/owner").param("owner", "true"),
            get("/api/owner").param(OwnerCookie.NAME, ownerValue),
            get("/api/owner").cookie(Cookie("study_visitor", ownerValue)),
            get("/api/owner").cookie(Cookie("chat_session", ownerValue)),
            get("/api/owner").cookie(Cookie("owner", "true")),
            get("/api/owner").cookie(Cookie("STUDY_OWNER", ownerValue)),
        )
        attempts.forEachIndexed { index, request -> assertEquals(false, ownerOf(perform(request)), "시도 $index 가 주인이 되었다") }
    }

    @Test
    fun `같은 이름의 쿠키가 둘이면 주인이 아니다`() {
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie(), ownerCookie()))))
        assertEquals(false, ownerOf(perform(get("/api/owner").cookie(ownerCookie("ow1.1.x"), ownerCookie()))))
    }

    @Test
    fun `판정은 요청 로그에 결과와 까닭의 낱말로 남고 쿠키의 값과 서명은 남지 않는다`() {
        perform(get("/api/owner").header("X-Request-Id", "req-owner-yes").cookie(ownerCookie()))
        perform(get("/api/owner").header("X-Request-Id", "req-owner-none"))
        perform(get("/api/owner").header("X-Request-Id", "req-owner-forged").cookie(ownerCookie("ow1.$made.${signature.dropLast(1)}A")))
        perform(get("/api/owner").header("X-Request-Id", "req-owner-bad").cookie(ownerCookie("ow2.garbage")))
        perform(get("/api/owner").header("X-Request-Id", "req-owner-old").cookie(ownerCookie(OwnerMark.value(secret, 1L))))
        perform(get("/api/owner").header("X-Request-Id", "req-owner-nosecret").cookie(ownerCookie()), judgeSecret = null)

        fun lineOf(requestId: String) = logs.lines.filter { it.contains("$requestId:") && it.contains("owner=") }
        assertTrue(lineOf("req-owner-yes").any { it.contains("owner=true") }, logs.lines.toString())
        assertTrue(lineOf("req-owner-none").any { it.contains("owner=false") && it.contains("reason=no_cookie") }, logs.lines.toString())
        assertTrue(lineOf("req-owner-forged").any { it.contains("owner=false") && it.contains("reason=bad_signature") }, logs.lines.toString())
        assertTrue(lineOf("req-owner-bad").any { it.contains("owner=false") && it.contains("reason=malformed") }, logs.lines.toString())
        assertTrue(lineOf("req-owner-old").any { it.contains("owner=false") && it.contains("reason=expired") }, logs.lines.toString())
        assertTrue(lineOf("req-owner-nosecret").any { it.contains("owner=false") && it.contains("reason=no_secret") }, logs.lines.toString())

        val leaked = logs.lines.filter { line ->
            line.contains(signature) || line.contains(signature.dropLast(1)) || line.contains("garbage") || line.contains(ownerValue)
        }
        assertEquals(emptyList<String>(), leaked, "쿠키의 값이나 서명이 로그에 있다")
    }

    @Test
    fun `GET 이 아닌 메서드는 성공하지 않고 쿠키를 심지 않는다`() {
        listOf(post("/api/owner"), put("/api/owner"), delete("/api/owner")).forEach { request ->
            val response = perform(request.cookie(ownerCookie()))
            assertFalse(response.status in 200..299, "GET 이 아닌 메서드가 성공했다: ${response.status}")
            assertNull(response.getHeader("Set-Cookie"))
            assertFalse(response.contentAsString.contains("\"owner\""), response.contentAsString)
        }
    }
}
