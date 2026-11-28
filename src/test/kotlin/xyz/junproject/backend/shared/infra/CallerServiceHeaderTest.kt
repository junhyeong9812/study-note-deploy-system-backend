package xyz.junproject.backend.shared.infra

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.shared.api.DependencyHealthController
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * backend 가 **우리 서비스**를 부르는 호출에 자기 규약 이름을 `X-Caller-Service: backend` 로 싣는다(⑬ T4b · 통신 규약 §2.2 · §7.1 의 T4 줄).
 * - 우리 서비스로 가는 호출 = llm 의 `/rewrite` · `/chat`(첫 요청과 교체 중 다시 보내는 요청 모두). 머리글은 **정확히 하나** · 값은 `backend`(소문자 · 코드의 상수).
 * - 들어온 요청의 `X-Caller-Service` 를 옮겨 싣지 않는다 · 설정(`SERVER_NAME` 등)에서 읽지 않는다.
 * - 바깥(의존)에는 싣지 않는다 — 임베딩 · 검색 엔진 · 의존 상태의 확인 · Claude 실행 자리 · 옛 통로(규약 §2.1 의 서비스가 아니고 nginx 뒤에 있지 않다).
 * - 그 밖의 머리글(요청 ID · Content-Type) · 본문은 그대로.
 * 가짜는 실제 소켓의 JDK `HttpServer` — 받은 머리글을 그대로 본다.
 */
class CallerServiceHeaderTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun close() = servers.forEach { it.stop(0) }

    data class Received(val path: String, val headers: Map<String, List<String>>, val body: String)

    /** 길(path)마다 답을 정해 두는 가짜 서버 — /chat 은 차례로 [chatAnswers] */
    private inner class FakeServer(private val chatAnswers: List<String> = listOf(DONE_STREAM)) {
        val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
        private val chatCalls = AtomicInteger()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).also { server ->
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                received += Received(exchange.requestURI.path, exchange.requestHeaders.mapKeys { it.key.lowercase() }, body)
                val path = exchange.requestURI.path
                val (contentType, answer) = when {
                    path == "/rewrite" -> "application/json" to """{"success":true,"data":{"keywords":["k"],"expanded":[],"filters":{}}}"""
                    path == "/chat" -> "application/x-ndjson" to chatAnswers[minOf(chatCalls.getAndIncrement(), chatAnswers.size - 1)]
                    path == "/embed" -> "application/json" to """{"dense":[[0.0,0.1]]}"""
                    path.endsWith("/_count") -> "application/json" to """{"count":0}"""
                    path.endsWith("/_cluster/health") -> "application/json" to """{"status":"green"}"""
                    path == "/runs" -> "application/json" to """{"success":true,"data":{"text":"답","truncated":false}}"""
                    path == "/ask" -> "application/json" to """{"answer":"답"}"""
                    else -> "application/json" to "{}"
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

    private val messages = listOf(mapOf("role" to "user", "content" to "질문"))

    private fun rewrite(url: String, requestId: String = "req-caller-1") =
        LlmClient(mockk(relaxed = true), url).rewrite(requestId, "질의")

    private fun stream(url: String, requestId: String = "req-caller-2"): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        LlmChatStreamClient(ObjectMapper(), url, Duration.ofSeconds(5), Duration.ofSeconds(5)).stream(requestId, messages) { events += it }
        return events
    }

    private fun assertCallerIsBackend(received: Received, what: String) {
        assertEquals(listOf("backend"), received.headers["x-caller-service"], "$what: X-Caller-Service 가 정확히 하나의 backend 가 아니다")
    }

    // ---- 우리 서비스(llm) ----

    @Test
    fun `질의 다듬기는 X-Caller-Service backend 를 하나 싣고 요청 ID 와 본문은 그대로다`() {
        val llm = FakeServer()
        assertTrue(rewrite(llm.url).used, "준비: 가짜 llm 의 답을 쓰지 못했다")
        val request = llm.received.single()
        assertCallerIsBackend(request, "/rewrite")
        assertEquals(listOf("req-caller-1"), request.headers["x-request-id"], "요청 ID 가 바뀌었다")
        assertTrue(request.headers["content-type"]?.single()?.startsWith("application/json") == true, request.headers.toString())
        assertEquals(mapOf("request_id" to "req-caller-1", "query" to "질의"), ObjectMapper().readValue(request.body, Map::class.java))
    }

    @Test
    fun `채팅 스트림은 첫 요청과 다시 보내는 요청 모두 X-Caller-Service backend 를 하나 싣는다`() {
        val llm = FakeServer(listOf(RETRY_STREAM, RETRY_STREAM, DONE_STREAM))
        assertEquals(ChatEvent.Done, stream(llm.url).last())
        assertEquals(3, llm.received.size, "준비: 다시 보내지 않았다")
        llm.received.forEachIndexed { index, request ->
            assertCallerIsBackend(request, "/chat ${index + 1}번째")
            assertEquals(listOf("req-caller-2"), request.headers["x-request-id"], "${index + 1}번째 요청의 요청 ID")
        }
    }

    @Test
    fun `요청 ID 의 꼴이 틀려 그 머리글을 싣지 않는 호출에도 X-Caller-Service 는 싣는다`() {
        val llm = FakeServer()
        rewrite(llm.url, requestId = "bad:id")
        stream(llm.url, requestId = "bad:id")
        llm.received.forEach { request ->
            assertNull(request.headers["x-request-id"])
            assertCallerIsBackend(request, request.path)
        }
    }

    // ---- 들어온 값을 옮기지 않는다 · 설정에서 읽지 않는다 ----

    /** 들어온 요청 안에서 llm 을 부르는 이 테스트만의 컨트롤러 */
    @RestController
    class CallingController(private val llmUrl: String) {
        @GetMapping("/test/call-llm")
        fun callLlm(): String {
            LlmClient(mockk(relaxed = true), llmUrl).rewrite("req-caller-incoming", "질의")
            LlmChatStreamClient(ObjectMapper(), llmUrl, Duration.ofSeconds(5), Duration.ofSeconds(5))
                .stream("req-caller-incoming", listOf(mapOf("role" to "user", "content" to "질문"))) {}
            return "ok"
        }
    }

    @Test
    fun `들어온 요청의 X-Caller-Service 를 옮겨 싣지 않고 자기 이름을 싣는다`() {
        listOf("front", "edge", "outside", "admin", "BACKEND", "backend, front").forEach { incoming ->
            val llm = FakeServer()
            val mockMvc = MockMvcBuilders.standaloneSetup(CallingController(llm.url)).build()
            val response = mockMvc.perform(get("/test/call-llm").header("X-Caller-Service", incoming)).andReturn().response
            assertEquals(200, response.status, "준비: $incoming")
            assertEquals(2, llm.received.size)
            llm.received.forEach { request -> assertCallerIsBackend(request, "들어온 값 $incoming · ${request.path}") }
        }
    }

    @Test
    fun `설정의 이름을 다르게 두어도 backend 다`() {
        val previous = System.getProperty("SERVER_NAME")
        System.setProperty("SERVER_NAME", "front")
        try {
            val llm = FakeServer()
            rewrite(llm.url)
            stream(llm.url)
            llm.received.forEach { request -> assertCallerIsBackend(request, request.path) }
        } finally {
            if (previous == null) System.clearProperty("SERVER_NAME") else System.setProperty("SERVER_NAME", previous)
        }
    }

    // ---- 바깥(의존)에는 싣지 않는다 ----

    @Test
    fun `임베딩과 검색 엔진에는 싣지 않는다`() {
        val outside = FakeServer()
        EmbeddingClient(outside.url).embedQuery("질의")
        runCatching { EsClient(outside.url).countByPath("cs/a.md") }
        assertTrue(outside.received.any { it.path == "/embed" } && outside.received.any { it.path.endsWith("/_count") }, "준비: ${outside.received.map { it.path }}")
        outside.received.forEach { request -> assertNull(request.headers["x-caller-service"], "바깥(${request.path})에 실었다") }
    }

    @Test
    fun `의존 상태의 확인에는 싣지 않는다`() {
        val outside = FakeServer()
        DependencyHealthController(DependencyHealthController.defaultChecks(
            mockk<StringRedisTemplate>(relaxed = true), mockk<JdbcTemplate>(relaxed = true), outside.url, outside.url,
        )).dependencies()
        assertEquals(2, outside.received.size, "준비: ${outside.received.map { it.path }}")
        outside.received.forEach { request -> assertNull(request.headers["x-caller-service"], "바깥(${request.path})에 실었다") }
    }

    @Test
    fun `Claude 실행 자리와 옛 통로에는 싣지 않는다`() {
        val runner = FakeServer()
        ClaudeRunnerClient(runner.url, "runner-secret-for-caller-test", ObjectMapper()).ask("req-caller-3", "prompt", Instant.now().plusSeconds(5))
        val bridge = FakeServer()
        ClaudeBridgeClient(ObjectMapper(), bridge.url, "bridge-secret-for-caller-test").ask("req-caller-4", "prompt", Instant.now().plusSeconds(5))
        val calls = runner.received + bridge.received
        assertEquals(listOf("/runs", "/ask"), calls.map { it.path }, "준비")
        calls.forEach { request -> assertNull(request.headers["x-caller-service"], "${request.path} 에 실었다") }
        assertEquals(listOf("runner-secret-for-caller-test"), runner.received.single().headers["x-claude-runner-secret"], "다른 머리글이 바뀌었다")
    }
}
