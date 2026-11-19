package xyz.junproject.backend.chat.usecase

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

class ChatServiceTest {
    private val listOperations = mockk<ListOperations<String, String>>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true).also {
        every { it.opsForList() } returns listOperations
    }

    private fun serviceEmitting(vararg events: ChatEvent): ChatService {
        val stream = object : ChatStreamPort {
            override fun stream(requestId: String, messages: List<Map<String, String>>,
                                onEvent: (ChatEvent) -> Unit) = events.forEach(onEvent)
        }
        return ChatService(mockk(relaxed = true), stream, mockk(relaxed = true), redis,
            mockk(relaxed = true), ObjectMapper())
    }

    private fun run(service: ChatService): List<ChatEvent> {
        val received = mutableListOf<ChatEvent>()
        service.chat("req-1", "session", "cs/a.md", "질문") { received += it }
        return received
    }

    @Test
    fun `완료된 답변만 이력에 남긴다 - 질문과 이어 붙인 답변`() {
        val received = run(serviceEmitting(ChatEvent.Queued(1), ChatEvent.Start,
            ChatEvent.Token("가"), ChatEvent.Token("나"), ChatEvent.Done))
        assertEquals(5, received.size)                                  // 이벤트는 전부 그대로 전달
        verify(exactly = 1) { listOperations.rightPush("chat:session:cs/a.md", match { it.contains("질문") }) }
        verify(exactly = 1) { listOperations.rightPush("chat:session:cs/a.md", match { it.contains("가나") }) }
    }

    @Test
    fun `대기 초과 오류로 끝나면 이력에 남기지 않는다`() {
        val received = run(serviceEmitting(ChatEvent.Queued(1), ChatEvent.Error("queue_timeout")))
        assertEquals(ChatEvent.Error("queue_timeout"), received.last())
        verify(exactly = 0) { listOperations.rightPush(any(), any<String>()) }
    }

    @Test
    fun `생성 도중 오류로 끊기면 받은 토큰이 있어도 이력에 남기지 않는다`() {
        run(serviceEmitting(ChatEvent.Start, ChatEvent.Token("반쯤"), ChatEvent.Error("upstream")))
        verify(exactly = 0) { listOperations.rightPush(any(), any<String>()) }
    }

    @Test
    fun `연결 실패는 그대로 던져 호출부가 서버 꺼짐으로 구별하게 한다`() {
        val stream = object : ChatStreamPort {
            override fun stream(requestId: String, messages: List<Map<String, String>>,
                                onEvent: (ChatEvent) -> Unit) =
                throw LlmUnreachable(java.net.ConnectException("refused"))
        }
        val service = ChatService(mockk(relaxed = true), stream, mockk(relaxed = true), redis,
            mockk(relaxed = true), ObjectMapper())
        assertThrows(LlmUnreachable::class.java) { service.chat("req-1", "s", "cs/a.md", "q") {} }
        verify(exactly = 0) { listOperations.rightPush(any(), any<String>()) }
    }

    @Test
    fun `완료 후 마지막 전달이 실패해도 완성된 답은 이력에 남긴다`() {
        val service = serviceEmitting(ChatEvent.Start, ChatEvent.Token("완성"), ChatEvent.Done)
        assertThrows(IllegalStateException::class.java) {
            service.chat("req-1", "session", "cs/a.md", "질문") { event ->
                if (event is ChatEvent.Done) throw IllegalStateException("client gone")
            }
        }
        verify(exactly = 1) { listOperations.rightPush("chat:session:cs/a.md", match { it.contains("완성") }) }
    }
}
