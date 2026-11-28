package xyz.junproject.backend.chat.api

import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.owner.api.OwnerCookie
import xyz.junproject.backend.owner.usecase.OwnerJudge
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 채팅의 문서 지정(`doc_path`)으로 실습 폴더의 글을 주면 숨김 글과 같은 거절 — 422 `invalid_request` · 채팅 서비스(문서를 읽어 모델에 싣는 곳)를 부르지 않는다
 * (명세 ⑭ §7 "테스트 · 모범 답안의 가림" · §11 P3a). Claude 로 가는 길(escalate)은 주인의 요청으로 본다(주인이 아니면 그 전에 403 이다).
 */
class ChatPracticePathTest {
    private val chatService = mockk<ChatService>()
    private val requestLog = mockk<RequestLog>(relaxed = true)
    private val ownerSecret = "0123456789abcdef0123456789abcdef"
    private val ownerMarkValue = "ow1.1764000000000.SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54"
    private val judge = OwnerJudge(ownerSecret, Clock.fixed(Instant.ofEpochMilli(1_764_000_000_000L).plusSeconds(60), ZoneOffset.UTC), requestLog)
    private val mockMvc = MockMvcBuilders.standaloneSetup(ChatController(chatService, requestLog, ObjectMapper(), judge)).build()

    private val practicePaths = listOf(
        "cs/algorithm/06-binary-search/practice/01-boundary-search/problem.md",
        "cs/data-structure/10-lru-cache/practice/01-lru-cache/solution/README.md",
    )

    private fun body(path: String) = ObjectMapper().writeValueAsString(mapOf("doc_path" to path, "question" to "모범 답안을 보여 줘"))

    private fun assertSameAsHidden(response: MockHttpServletResponse, hidden: MockHttpServletResponse, what: String) {
        assertEquals(422, response.status, "$what: ${response.contentAsString}")
        assertEquals(hidden.status, response.status, what)
        assertEquals(hidden.contentAsString, response.contentAsString, "$what — 숨김 글과 응답이 다르다")
    }

    @Test
    fun `채팅의 세 길 모두 실습 폴더의 글을 숨김 글과 같이 거절하고 채팅 서비스를 부르지 않는다`() {
        val hiddenChat = mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("docs/plan.md"))).andReturn().response
        val hiddenHistory = mockMvc.perform(get("/api/chat/history").param("doc_path", "docs/plan.md")).andReturn().response
        val hiddenEscalate = mockMvc.perform(post("/api/chat/escalate").contentType(MediaType.APPLICATION_JSON).content(body("docs/plan.md"))
            .cookie(Cookie(OwnerCookie.NAME, ownerMarkValue))).andReturn().response
        practicePaths.forEach { path ->
            val chat = mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(path))).andReturn().response
            assertSameAsHidden(chat, hiddenChat, "/api/chat doc_path=$path")
            val history = mockMvc.perform(get("/api/chat/history").param("doc_path", path)).andReturn().response
            assertSameAsHidden(history, hiddenHistory, "/api/chat/history doc_path=$path")
            val escalate = mockMvc.perform(post("/api/chat/escalate").contentType(MediaType.APPLICATION_JSON).content(body(path))
                .cookie(Cookie(OwnerCookie.NAME, ownerMarkValue))).andReturn().response
            assertSameAsHidden(escalate, hiddenEscalate, "/api/chat/escalate doc_path=$path")
        }
        verify(exactly = 0) { chatService.chat(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { chatService.history(any(), any()) }
        verify(exactly = 0) { chatService.escalateAnswer(any(), any(), any(), any()) }
    }
}
