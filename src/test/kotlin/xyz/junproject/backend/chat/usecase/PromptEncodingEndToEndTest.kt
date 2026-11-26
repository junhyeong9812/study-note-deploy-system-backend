package xyz.junproject.backend.chat.usecase

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalateFixtures.DOC_PATH
import xyz.junproject.backend.chat.usecase.EscalateFixtures.SESSION
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.infra.ClaudeRunnerClient
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 짝 글자를 자른 프롬프트를 실행 자리가 거절하는 일이 없다(명세 §22 ①) — 끝에서 끝으로: 실제 [ChatService] · 실제 [ClaudeRunnerClient] ·
 * 실제 실행 자리처럼 프롬프트를 UTF-8 로 엄격하게 바꿀 수 없으면 422 `unencodable_prompt`(몸을 읽지 못하면 422 `invalid_body`)를 내는 가짜.
 * 재현의 입력(문서 `"a"*39999 + "🎉" + "b"*100` · 이력의 답 `"a"*499 + "🎉🎉"`)으로 자동 넘김이 답을 받는다.
 */
class PromptEncodingEndToEndTest {
    private val logs = LogCapture()
    private var server: HttpServer? = null

    @AfterEach
    fun close() {
        server?.stop(0)
        logs.close()
    }

    private fun strictlyEncodable(text: String): Boolean = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    @Test
    fun `짝 글자가 자르는 자리에 걸린 문서와 이력으로도 실행 자리가 거절하지 않고 답이 온다`() {
        val answered = AtomicInteger()
        val rejected = AtomicInteger()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).also { created ->
            created.createContext("/runs") { exchange ->
                val raw = exchange.requestBody.readAllBytes()
                val (status, body) = try {
                    val prompt = ObjectMapper().readTree(raw).path("prompt").asString()
                    if (!strictlyEncodable(prompt)) { rejected.incrementAndGet(); 422 to """{"success":false,"error":{"code":"unencodable_prompt"}}""" }
                    else { answered.incrementAndGet(); 200 to """{"success":true,"data":{"text":"ANSWER-MARKER","truncated":false}}""" }
                } catch (_: Exception) {
                    rejected.incrementAndGet(); 422 to """{"success":false,"error":{"code":"invalid_body"}}"""
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            created.start()
        }
        val client = ClaudeRunnerClient("http://127.0.0.1:${server!!.address.port}", "runner-secret-for-encoding-test", ObjectMapper())
        val source = io.mockk.mockk<NoteSourcePort>().also { io.mockk.every { it.readFile(any()) } returns "a".repeat(39_999) + "🎉" + "b".repeat(100) }
        val history = listOf(ObjectMapper().writeValueAsString(mapOf("role" to "assistant", "content" to "a".repeat(499) + "🎉🎉")))
        val service = ChatService(source, FakeStream { throw LlmUnreachable(ConnectException("refused"), neverConnected = true) }, client,
            EscalateFixtures.FakeRedis(history).redis, logs.requestLog, EscalateFixtures.objectMapper, Clock.systemUTC())

        val events = mutableListOf<ChatEvent>()
        service.chat("req-encoding-1", SESSION, DOC_PATH, "질문", true, Instant.now()) { events += it }

        assertEquals(0, rejected.get(), "실행 자리가 프롬프트를 거절했다")
        assertEquals(1, answered.get())
        assertEquals(listOf(ChatEvent.Escalated, ChatEvent.Token("ANSWER-MARKER"), ChatEvent.Done), events)
    }
}
