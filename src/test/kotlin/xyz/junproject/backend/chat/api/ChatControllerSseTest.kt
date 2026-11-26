package xyz.junproject.backend.chat.api

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import xyz.junproject.backend.shared.infra.RequestLog

/** 브라우저가 받는 바이트 기준으로 SSE 계약을 확인한다. */
class ChatControllerSseTest {
    private val chatService = mockk<ChatService>()
    private val requestLog = mockk<RequestLog>(relaxed = true).also {
        every { it.acceptOrIssue(any()) } returns "req-1"
    }
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ChatController(chatService, requestLog, ObjectMapper())).build()
    private val validBody = """{"doc_path":"cs/a.md","question":"질문"}"""

    private fun streamedBody(accept: String): String {
        val result = mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .accept(accept).content(validBody))
            .andExpect(request().asyncStarted()).andReturn()
        val deadline = System.currentTimeMillis() + 5_000        // 스트림 작업은 다른 스레드 — 완료까지 대기
        while (!result.request.asyncContext!!.let { result.request.isAsyncStarted.not() } &&
            System.currentTimeMillis() < deadline &&
            !result.response.contentAsString.let { it.contains("event:done") || it.contains("event:error") || it.contains("[오류]") || it.endsWith("끝") }) {
            Thread.sleep(20)
        }
        Thread.sleep(50)
        return result.response.getContentAsString(Charsets.UTF_8)
    }

    @Test
    fun `SSE 요청이면 대기·시작·토큰·완료가 이벤트로 나간다`() {
        every { chatService.chat(any(), any(), any(), any(), any(), any(), any()) } answers {
            val onEvent = lastArg<(ChatEvent) -> Unit>()
            listOf(ChatEvent.Queued(2), ChatEvent.Start, ChatEvent.Token("안녕\n하세요"), ChatEvent.Done)
                .forEach(onEvent)
        }
        val body = streamedBody(MediaType.TEXT_EVENT_STREAM_VALUE)
        val queuedAt = body.indexOf("event:queued\ndata:{\"position\":2}")
        val startAt = body.indexOf("event:start")
        val tokenAt = body.indexOf("event:token\ndata:{\"text\":\"안녕\\n하세요\"}")   // 줄바꿈은 JSON 안에서 이스케이프
        val doneAt = body.indexOf("event:done")
        assertTrue(queuedAt >= 0 && queuedAt < startAt && startAt < tokenAt && tokenAt < doneAt, body)
    }

    @Test
    fun `llm 연결 실패는 llm_unreachable 오류 이벤트 - 본문에 글자를 섞지 않는다`() {
        every { chatService.chat(any(), any(), any(), any(), any(), any(), any()) } throws
            LlmUnreachable(java.net.ConnectException("refused"))
        val body = streamedBody(MediaType.TEXT_EVENT_STREAM_VALUE)
        assertTrue(body.contains("event:error\ndata:{\"code\":\"llm_unreachable\"}"), body)
        assertFalse(body.contains("[오류]"), body)
    }

    @Test
    fun `그 밖의 실패는 internal 오류 이벤트`() {
        every { chatService.chat(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
        assertTrue(streamedBody(MediaType.TEXT_EVENT_STREAM_VALUE)
            .contains("event:error\ndata:{\"code\":\"internal\"}"))
    }

    @Test
    fun `SSE를 요청하지 않으면 구 계약 - 토큰 글자만 흘린다`() {
        every { chatService.chat(any(), any(), any(), any(), any(), any(), any()) } answers {
            val onEvent = lastArg<(ChatEvent) -> Unit>()
            listOf(ChatEvent.Queued(1), ChatEvent.Start, ChatEvent.Token("답"), ChatEvent.Token("끝"), ChatEvent.Done)
                .forEach(onEvent)
        }
        assertEquals("답끝", streamedBody(MediaType.TEXT_PLAIN_VALUE))
    }

    @Test
    fun `입력 검증 실패는 스트림이 아니라 422 봉투`() {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE).content("""{"doc_path":"../etc/passwd","question":"q"}"""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("invalid_request"))
    }
}
