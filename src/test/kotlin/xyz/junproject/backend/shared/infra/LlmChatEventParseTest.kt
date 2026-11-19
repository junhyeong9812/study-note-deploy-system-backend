package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent

class LlmChatEventParseTest {
    private val client = LlmChatStreamClient(ObjectMapper())

    @Test
    fun `다섯 종류 이벤트 줄을 그대로 옮긴다`() {
        assertEquals(ChatEvent.Queued(3), client.parseEventLine("""{"type":"queued","position":3}"""))
        assertEquals(ChatEvent.Start, client.parseEventLine("""{"type":"start"}"""))
        assertEquals(ChatEvent.Token("안녕\n하세요"), client.parseEventLine("""{"type":"token","text":"안녕\n하세요"}"""))
        assertEquals(ChatEvent.Error("queue_full"), client.parseEventLine("""{"type":"error","code":"queue_full"}"""))
        assertEquals(ChatEvent.Done, client.parseEventLine("""{"type":"done"}"""))
    }

    @Test
    fun `계약 밖 줄은 버린다 - 스트림을 깨지 않는다`() {
        assertNull(client.parseEventLine("""{"type":"unknown"}"""))
        assertNull(client.parseEventLine("not json"))
        assertNull(client.parseEventLine("""{"text":"type 없음"}"""))
    }
}
