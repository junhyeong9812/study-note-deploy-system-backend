package xyz.junproject.backend.chat.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.chat.usecase.EscalatePort
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.owner.api.OwnerCookie
import xyz.junproject.backend.owner.domain.OwnerMark
import xyz.junproject.backend.owner.usecase.OwnerJudge
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * 주인이 아닌 방문자의 질문은 어떤 경우에도 Claude 로 가지 않는다(명세 §2 첫 불변식 · §17).
 * Claude 로 가는 길은 지금 하나다: `POST /api/chat/escalate` → [ChatService.escalateAnswer] → [EscalatePort.ask](ClaudeBridgeClient).
 * - 주인이 아니면 403 `owner_only` — 몸의 검증(문서 경로 · 질문)보다 먼저 판정한다(주인이 아니면 다른 검증의 결과를 보이지 않는다).
 *   Claude 쪽을 한 번도 부르지 않고, 대화 이력(Redis)을 읽지도 쓰지도 않으며, 세션 쿠키를 심지 않는다.
 * - 주인의 요청은 지금 동작 그대로(답 · 이력 둘 · 통로가 없으면 503 escalate_unavailable · 실패면 503 escalate_failed · 몸이 틀리면 422).
 * 실제 [ChatService] 에 가짜 통로(부른 수를 센다)와 가짜 Redis 를 잇는다.
 */
class ChatEscalateOwnerTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val made = 1_764_000_000_000L
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(made).plusSeconds(3600), ZoneOffset.UTC)
    private val ownerValue = "ow1.1764000000000.SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54"

    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()

    private val asked = AtomicInteger()
    private var bridgeAvailable = true
    private var bridgeFails = false
    private val bridge = object : EscalatePort {
        override val available: Boolean get() = bridgeAvailable
        override val autoEscalation: Boolean get() = false
        override fun ask(requestId: String, prompt: String, deadline: Instant): xyz.junproject.backend.chat.usecase.EscalateResult {
            asked.incrementAndGet()
            if (bridgeFails) return xyz.junproject.backend.chat.usecase.EscalateResult.Refused(xyz.junproject.backend.chat.usecase.EscalateRefusal.UNAVAILABLE)
            return xyz.junproject.backend.chat.usecase.EscalateResult.Answered("claude answer", false)
        }
    }
    private val listOperations = mockk<ListOperations<String, String>>(relaxed = true).also {
        every { it.range(any(), any(), any()) } returns emptyList()
    }
    private val redis = mockk<StringRedisTemplate>(relaxed = true).also { every { it.opsForList() } returns listOperations }
    private val source = mockk<NoteSourcePort>().also { every { it.readFile(any()) } returns "# note\n" }

    private val chatService = ChatService(source, mockk(relaxed = true), bridge, redis, logs.requestLog, ObjectMapper())

    private fun mockMvc(judgeSecret: String? = secret) = MockMvcBuilders
        .standaloneSetup(ChatController(chatService, logs.requestLog, ObjectMapper(), OwnerJudge(judgeSecret, clock, logs.requestLog)))
        .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    private val validBody = """{"doc_path":"cs/os/process/2-summary.md","question":"what is a process?"}"""

    private fun escalate(body: String = validBody, judgeSecret: String? = secret, customize: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = { it }): MockHttpServletResponse =
        mockMvc(judgeSecret).perform(customize(post("/api/chat/escalate").contentType(MediaType.APPLICATION_JSON).content(body))).andReturn().response

    private fun asOwner(request: MockHttpServletRequestBuilder) = request.cookie(Cookie(OwnerCookie.NAME, ownerValue))

    private fun assertOwnerOnly(response: MockHttpServletResponse, what: String) {
        assertEquals(403, response.status, "$what: ${response.contentAsString}")
        assertTrue(response.contentAsString.contains("\"owner_only\""), "$what: ${response.contentAsString}")
        assertTrue(response.contentAsString.contains("\"success\":false"), "$what: ${response.contentAsString}")
        assertFalse(response.contentAsString.contains("claude answer"), what)
        assertNull(response.getHeader("Set-Cookie"), "$what: 주인이 아닌데 쿠키를 심었다")
    }

    private fun assertClaudeUntouched() {
        assertEquals(0, asked.get(), "주인이 아닌데 Claude 쪽을 불렀다")
        verify(exactly = 0) { redis.opsForList() }
        verify(exactly = 0) { redis.expire(any(), any<java.time.Duration>()) }
        verify(exactly = 0) { source.readFile(any()) }
    }

    // ---- 주인이 아닌 요청 ----

    @Test
    fun `표시가 없거나 틀린 요청은 403 owner_only 이고 Claude 쪽과 이력에 닿지 않는다`() {
        val signature = ownerValue.substringAfterLast('.')
        val attempts = mapOf<String, (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder>(
            "쿠키 없음" to { it },
            "서명 한 글자" to { it.cookie(Cookie(OwnerCookie.NAME, "ow1.$made.${signature.dropLast(1)}A")) },
            "다른 열쇠" to { it.cookie(Cookie(OwnerCookie.NAME, OwnerMark.value("fedcba9876543210fedcba9876543210", made))) },
            "만료" to { it.cookie(Cookie(OwnerCookie.NAME, OwnerMark.value(secret, 1L))) },
            "꼴이 틀림" to { it.cookie(Cookie(OwnerCookie.NAME, "ow2.$made.$signature")) },
            "쿠키 둘" to { it.cookie(Cookie(OwnerCookie.NAME, ownerValue), Cookie(OwnerCookie.NAME, ownerValue)) },
        )
        attempts.forEach { (what, customize) -> assertOwnerOnly(escalate(customize = customize), what) }
        assertClaudeUntouched()
    }

    @Test
    fun `머리 질의 다른 쿠키의 글자로는 Claude 로 가지 못한다`() {
        val attempts = mapOf<String, (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder>(
            "X-Owner 머리" to { it.header("X-Owner", "true") },
            "주인 표시를 머리로" to { it.header("X-Study-Owner", ownerValue) },
            "질의 owner=true" to { it.param("owner", "true") },
            "study_visitor 쿠키" to { it.cookie(Cookie("study_visitor", ownerValue)) },
            "chat_session 쿠키" to { it.cookie(Cookie("chat_session", ownerValue)) },
        )
        attempts.forEach { (what, customize) -> assertOwnerOnly(escalate(customize = customize), what) }
        assertClaudeUntouched()
    }

    @Test
    fun `몸에 주인이라는 칸을 넣어도 Claude 로 가지 못한다`() {
        val response = escalate(body = """{"doc_path":"cs/os/process/2-summary.md","question":"q","owner":true,"study_owner":"$ownerValue"}""")
        assertNotEquals(200, response.status, response.contentAsString)
        assertFalse(response.contentAsString.contains("claude answer"))
        assertClaudeUntouched()
    }

    @Test
    fun `주인이 아니면 몸이 틀려도 검증의 결과 대신 403 이다`() {
        listOf(
            """{"doc_path":"../secret.md","question":"q"}""",
            """{"doc_path":"cs/a.md","question":""}""",
            """{"doc_path":"docs/plan.md","question":"q"}""",
        ).forEach { body -> assertOwnerOnly(escalate(body = body), "몸: $body") }
        assertClaudeUntouched()
    }

    @Test
    fun `열쇠를 쓸 수 없으면 바르게 서명한 표시로도 Claude 로 가지 못한다`() {
        assertOwnerOnly(escalate(judgeSecret = null, customize = ::asOwner), "열쇠 없음")
        assertOwnerOnly(escalate(judgeSecret = "", customize = ::asOwner), "빈 열쇠")
        assertClaudeUntouched()
    }

    @Test
    fun `주인이 아닌 요청의 거절이 판정의 까닭과 함께 로그에 남고 쿠키의 값은 남지 않는다`() {
        escalate { it.header("X-Request-Id", "req-escalate-forged").cookie(Cookie(OwnerCookie.NAME, OwnerMark.value("fedcba9876543210fedcba9876543210", made))) }
        val lines = logs.lines.filter { it.contains("req-escalate-forged:") }
        assertTrue(lines.any { it.contains("owner=false") && it.contains("reason=bad_signature") }, logs.lines.toString())
        val forgedSignature = OwnerMark.signature("fedcba9876543210fedcba9876543210", made)
        assertTrue(logs.lines.none { it.contains(forgedSignature) }, "쿠키의 서명이 로그에 있다")
    }

    // ---- 주인의 요청 — 지금 동작 그대로 ----

    @Test
    fun `주인의 요청은 Claude 의 답을 받고 이력에 질문과 답을 남긴다`() {
        val response = escalate(customize = ::asOwner)
        assertEquals(200, response.status, response.contentAsString)
        assertTrue(response.contentAsString.contains("\"answer\":\"claude answer\""), response.contentAsString)
        assertEquals(1, asked.get())
        // 질문 · 답 두 줄은 한 명령으로 쓴다(명세 §21-8)
        verify(exactly = 1) { listOperations.rightPushAll(match { key -> key.startsWith("chat:") && key.endsWith(":cs/os/process/2-summary.md") }, any<String>(), any<String>()) }
    }

    @Test
    fun `주인의 요청이라도 통로가 없거나 실패하면 지금처럼 503 이다`() {
        bridgeAvailable = false
        val unavailable = escalate(customize = ::asOwner)
        assertEquals(503, unavailable.status, unavailable.contentAsString)
        assertTrue(unavailable.contentAsString.contains("\"escalate_unavailable\""), unavailable.contentAsString)

        bridgeAvailable = true
        bridgeFails = true
        val failed = escalate(customize = ::asOwner)
        assertEquals(503, failed.status, failed.contentAsString)
        assertTrue(failed.contentAsString.contains("\"escalate_failed\""), failed.contentAsString)
    }

    @Test
    fun `주인의 요청이라도 몸이 틀리면 지금처럼 422 이고 Claude 를 부르지 않는다`() {
        val response = escalate(body = """{"doc_path":"../secret.md","question":"q"}""", customize = ::asOwner)
        assertEquals(422, response.status, response.contentAsString)
        assertTrue(response.contentAsString.contains("\"invalid_request\""), response.contentAsString)
        assertEquals(0, asked.get())
    }

    @Test
    fun `주인의 판정도 로그에 남는다`() {
        escalate { asOwner(it.header("X-Request-Id", "req-escalate-owner")) }
        assertTrue(logs.lines.any { it.contains("req-escalate-owner:") && it.contains("owner=true") }, logs.lines.toString())
        assertTrue(logs.lines.none { it.contains(ownerValue.substringAfterLast('.')) }, "쿠키의 서명이 로그에 있다")
    }
}
