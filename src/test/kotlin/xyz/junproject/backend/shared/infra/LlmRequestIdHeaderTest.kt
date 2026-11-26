package xyz.junproject.backend.shared.infra

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * backend → llm 구간에서 추적 ID 가 머리글로 이어진다(통신 규약 §1.2 "앱의 의무" · §1.4 #5) — 실제 소켓의 가짜 llm(JDK `HttpServer`)이 받은 것을 값으로 본다.
 * - `/rewrite` · `/chat`(첫 요청과 교체 중 **다시 보내는 요청 모두**)에 `X-Request-Id: <그 요청의 추적 ID>` 가 실린다.
 * - 본문의 `request_id` 는 그대로(호환)이고 머리글과 같은 값이다.
 * - 추적 ID 의 꼴(`^[A-Za-z0-9_-]{1,64}$`)이 맞지 않으면(CR/LF · `:` 등 — `acceptOrIssue` 를 거치면 생기지 않는다) 머리글을 싣지 않는다
 *   (머리글 주입을 막는다 — 다른 머리글이 끼어들지 않는다). 호출은 그래도 간다(본문은 그대로).
 */
class LlmRequestIdHeaderTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun close() = servers.forEach { it.stop(0) }

    /** 받은 요청 하나 — 머리글(소문자 이름) · 본문 */
    data class Received(val path: String, val headers: Map<String, List<String>>, val body: String)

    private inner class FakeLlm(private val chatAnswers: List<String> = listOf(DONE_STREAM)) {
        val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
        private val chatCalls = AtomicInteger()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).also { server ->
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                received += Received(exchange.requestURI.path, exchange.requestHeaders.mapKeys { it.key.lowercase() }, body)
                val (contentType, answer) = when (exchange.requestURI.path) {
                    "/rewrite" -> "application/json" to """{"success":true,"data":{"keywords":["k"],"expanded":[],"filters":{}}}"""
                    else -> "application/x-ndjson" to chatAnswers[minOf(chatCalls.getAndIncrement(), chatAnswers.size - 1)]
                }
                val bytes = answer.toByteArray()
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            server.start()
            servers += server
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private companion object {
        const val DONE_STREAM = "{\"type\":\"start\"}\n{\"type\":\"token\",\"text\":\"답\"}\n{\"type\":\"done\"}\n"
        const val RETRY_STREAM = "{\"type\":\"retry\"}\n"
    }

    private val json = ObjectMapper()
    private fun bodyRequestId(received: Received): Any? = json.readValue(received.body, Map::class.java)["request_id"]
    private val messages = listOf(mapOf("role" to "user", "content" to "질문"))

    private fun stream(url: String, requestId: String): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        LlmChatStreamClient(ObjectMapper(), url, Duration.ofSeconds(5), Duration.ofSeconds(5)).stream(requestId, messages) { events += it }
        return events
    }

    @Test
    fun `질의 다듬기는 추적 ID 를 머리글과 본문에 같은 값으로 싣는다`() {
        val llm = FakeLlm()
        val outcome = LlmClient(mockk(relaxed = true), llm.url).rewrite("req-trace-rewrite-1", "질의")
        assertTrue(outcome.used, "준비: 가짜 llm 의 답을 쓰지 못했다")
        val request = llm.received.single()
        assertEquals("/rewrite", request.path)
        assertEquals(listOf("req-trace-rewrite-1"), request.headers["x-request-id"])
        assertEquals("req-trace-rewrite-1", bodyRequestId(request), "본문의 request_id 가 바뀌었다")
    }

    @Test
    fun `채팅 스트림은 추적 ID 를 머리글과 본문에 같은 값으로 싣는다`() {
        val llm = FakeLlm()
        assertEquals(ChatEvent.Done, stream(llm.url, "req-trace-chat-1").last())
        val request = llm.received.single()
        assertEquals("/chat", request.path)
        assertEquals(listOf("req-trace-chat-1"), request.headers["x-request-id"])
        assertEquals("req-trace-chat-1", bodyRequestId(request))
    }

    @Test
    fun `교체 중이라 다시 보내는 요청에도 같은 추적 ID 를 머리글과 본문에 싣는다`() {
        val llm = FakeLlm(listOf(RETRY_STREAM, RETRY_STREAM, DONE_STREAM))
        assertEquals(ChatEvent.Done, stream(llm.url, "req-trace-resend-1").last())
        assertEquals(3, llm.received.size, "준비: 다시 보내지 않았다 ${llm.received.size}")
        llm.received.forEachIndexed { index, request ->
            assertEquals(listOf("req-trace-resend-1"), request.headers["x-request-id"], "${index + 1}번째 요청의 머리글")
            assertEquals("req-trace-resend-1", bodyRequestId(request), "${index + 1}번째 요청의 본문")
        }
    }

    @Test
    fun `꼴이 틀린 추적 ID 는 머리글에 싣지 않고 다른 머리글이 끼어들지 않으며 호출은 간다`() {
        val malformed = listOf("req-1\r\nX-Injected: yes", "req-1\nX-Injected: yes", "req:1", "req 1", "a".repeat(65), "")
        malformed.forEach { requestId ->
            val llm = FakeLlm()
            LlmClient(mockk(relaxed = true), llm.url).rewrite(requestId, "질의")
            val failure = runCatching { stream(llm.url, requestId) }.exceptionOrNull()
            assertNull(failure, "채팅 스트림이 던졌다")
            assertEquals(2, llm.received.size, "호출이 가지 않았다: ${requestId.replace("\r", "\\r").replace("\n", "\\n")}")
            llm.received.forEach { request ->
                assertNull(request.headers["x-request-id"], "꼴이 틀린 ID 를 머리글에 실었다: ${request.headers["x-request-id"]}")
                assertNull(request.headers["x-injected"], "머리글이 끼어들었다")
            }
        }
    }

    @Test
    fun `꼴의 경계 - 64자와 밑줄과 하이픈은 싣는다`() {
        listOf("a".repeat(64), "req_trace-ID_1", "0").forEach { requestId ->
            val llm = FakeLlm()
            LlmClient(mockk(relaxed = true), llm.url).rewrite(requestId, "질의")
            assertEquals(listOf(requestId), llm.received.single().headers["x-request-id"], requestId)
        }
    }
}
