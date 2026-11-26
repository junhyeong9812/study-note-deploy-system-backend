package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread

/**
 * 로컬 모델의 "연결되지 못함" 가르기(명세 §21-1) — 실제 소켓으로.
 * 그 질문의 **첫 시도가 연결 단계에서** 실패한 것만 [LlmUnreachable.neverConnected] 가 참이다: 연결 거부 · 연결 시간 초과 · 주소를 풀지 못함.
 * 헤더 시간 초과 · 연결 직후 끊김 · 교체 중(retry) 다시 보낸 요청의 실패는 거짓 — 서버가 요청을 받았거나 받았을 수 있다.
 * 어느 쪽이든 화면의 코드(`llm_unreachable`)는 지금 그대로다(LlmUnreachable 을 던진다).
 */
class LlmConnectStageTest {
    private val sockets = mutableListOf<AutoCloseable>()

    @AfterEach
    fun close() = sockets.forEach { runCatching { it.close() } }

    private fun client(url: String, headerTimeout: Duration = Duration.ofMillis(800)) =
        LlmChatStreamClient(ObjectMapper(), url, Duration.ofSeconds(5), headerTimeout)

    private val messages = listOf(mapOf("role" to "user", "content" to "질문"))

    private fun failureOf(url: String, headerTimeout: Duration = Duration.ofMillis(800)): LlmUnreachable {
        val events = mutableListOf<ChatEvent>()
        val failure = assertThrows(LlmUnreachable::class.java) { client(url, headerTimeout).stream("req-llm-1", messages) { events += it } }
        assertEquals(emptyList<ChatEvent>(), events, "연결하지 못했는데 이벤트가 나갔다")
        return failure
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `첫 시도의 연결 거부는 연결되지 못함이다`() {
        assertTrue(failureOf("http://127.0.0.1:${closedPort()}").neverConnected)
    }

    @Test
    fun `주소를 풀지 못하면 연결되지 못함이다`() {
        assertTrue(failureOf("http://llm-host-that-does-not-exist.invalid:8000").neverConnected)
    }

    @Test
    fun `연결 시간 초과는 연결되지 못함이다`() {
        // 받아들이지 않는 서버의 대기 줄을 채운다 — 그다음 연결은 핸드셰이크가 끝나지 않는다(리눅스는 SYN 을 버린다)
        val server = ServerSocket().also { it.bind(InetSocketAddress("127.0.0.1", 0), 1); sockets += it }
        repeat(8) {
            runCatching { Socket().also { socket -> sockets += socket; socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 200) } }
        }
        val started = System.nanoTime()
        val failure = failureOf("http://127.0.0.1:${server.localPort}", headerTimeout = Duration.ofSeconds(30))
        assertTrue(failure.neverConnected, "연결 시간 초과를 연결된 것으로 봤다: ${failure.cause}")
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 10_000)
    }

    @Test
    fun `연결한 뒤 헤더가 늦으면 연결되지 못함이 아니다`() {
        val server = ServerSocket(0).also { sockets += it }
        thread(isDaemon = true) { runCatching { server.accept().also { sockets += it }; Thread.sleep(20_000) } }
        assertFalse(failureOf("http://127.0.0.1:${server.localPort}").neverConnected, "헤더 시간 초과를 넘김 조건으로 봤다")
    }

    @Test
    fun `연결 직후 끊기면 연결되지 못함이 아니다`() {
        val server = ServerSocket(0).also { sockets += it }
        thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
        assertFalse(failureOf("http://127.0.0.1:${server.localPort}").neverConnected, "연결 직후 끊김을 넘김 조건으로 봤다")
    }

    @Test
    fun `교체 중이라 다시 보낸 요청이 연결하지 못하면 연결되지 못함이 아니다`() {
        // 첫 요청에는 retry 를 답하고 서버를 닫는다 — 다시 보낸 요청은 연결이 거부된다
        val server = ServerSocket(0).also { sockets += it }
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { connection ->
                    val input = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val body = "{\"type\":\"retry\"}\n"
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nContent-Length: ${body.length}\r\n" +
                        "Connection: close\r\n\r\n$body").toByteArray(Charsets.ISO_8859_1))
                    connection.getOutputStream().flush()
                }
                server.close()
            }
        }
        val failure = failureOf("http://127.0.0.1:${server.localPort}")
        assertFalse(failure.neverConnected, "교체 중 다시 보낸 요청의 실패를 넘김 조건으로 봤다")
    }
}
