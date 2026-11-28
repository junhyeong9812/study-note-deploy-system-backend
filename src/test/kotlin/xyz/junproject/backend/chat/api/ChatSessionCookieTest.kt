package xyz.junproject.backend.chat.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 명세 2.1 — `chat_session` 은 그대로 동작하고(특성: 값의 꼴 · 30일 · HttpOnly · Path=/ · 유효하면 다시 내지 않음),
 * 빠져 있던 `Secure` · `SameSite=Lax` 가 붙는다.
 */
class ChatSessionCookieTest {
    private val chatService = mockk<ChatService>().also {
        every { it.history(any(), any()) } returns emptyList()
    }
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ChatController(chatService, mockk<RequestLog>(relaxed = true), ObjectMapper())).build()

    private fun historyResponse(vararg cookies: Cookie) =
        mockMvc.perform(get("/api/chat/history").param("doc_path", "cs/a.md").apply { if (cookies.isNotEmpty()) cookie(*cookies) })
            .andReturn().response

    /** Set-Cookie 한 줄을 이름=값과 속성(소문자 이름 → 값)으로 나눈다 */
    private fun parse(header: String): Pair<String, Map<String, String>> {
        val parts = header.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val attributes = parts.drop(1).associate { part ->
            val name = part.substringBefore("=").lowercase()
            name to (if (part.contains("=")) part.substringAfter("=") else "")
        }
        return parts.first() to attributes
    }

    private fun issuedChatCookie(header: List<String>): Pair<String, Map<String, String>> {
        val chatCookies = header.filter { it.startsWith("chat_session=") }
        assertEquals(1, chatCookies.size, header.toString())
        return parse(chatCookies.single())
    }

    @Test
    fun `세션이 없으면 UUID 값의 chat_session 을 30일 HttpOnly Path=루트로 발급한다`() {
        val response = historyResponse()
        val (pair, attributes) = issuedChatCookie(response.getHeaders("Set-Cookie"))
        val value = pair.substringAfter("chat_session=")
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(value), value)
        assertEquals("2592000", attributes["max-age"])
        assertEquals("/", attributes["path"])
        assertTrue(attributes.containsKey("httponly"), attributes.toString())
        verify(exactly = 1) { chatService.history(value, "cs/a.md") }
    }

    @Test
    fun `발급하는 chat_session 에 Secure 와 SameSite=Lax 가 붙는다`() {
        val (_, attributes) = issuedChatCookie(historyResponse().getHeaders("Set-Cookie"))
        assertTrue(attributes.containsKey("secure"), attributes.toString())
        assertEquals("lax", attributes["samesite"]?.lowercase(), attributes.toString())
    }

    @Test
    fun `유효한 세션이면 쿠키를 다시 내지 않고 그 값으로 이력을 읽는다`() {
        val existing = "123e4567-e89b-12d3-a456-426614174000"
        val response = historyResponse(Cookie("chat_session", existing))
        assertEquals(emptyList<String>(), response.getHeaders("Set-Cookie").filter { it.startsWith("chat_session=") })
        verify(exactly = 1) { chatService.history(existing, "cs/a.md") }
    }

    @Test
    fun `꼴이 틀린 세션 값은 쓰지 않고 새로 발급한다`() {
        val response = historyResponse(Cookie("chat_session", "forged-value"))
        val (pair, _) = issuedChatCookie(response.getHeaders("Set-Cookie"))
        val issued = pair.substringAfter("chat_session=")
        assertNotEquals("forged-value", issued)
        verify(exactly = 1) { chatService.history(issued, "cs/a.md") }
        verify(exactly = 0) { chatService.history("forged-value", any()) }
    }
}
