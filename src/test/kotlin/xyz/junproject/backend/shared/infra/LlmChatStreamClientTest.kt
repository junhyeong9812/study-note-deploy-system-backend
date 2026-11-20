package xyz.junproject.backend.shared.infra

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 가짜 llm(로컬 HTTP 서버)을 상대로 스트림 계약을 확인한다 — 연결 실패·절단·무응답의 구별이 핵심. */
class LlmChatStreamClientTest {
    private var server: HttpServer? = null
    private val releaseStalledHandler = CountDownLatch(1)

    @AfterEach
    fun stopServer() {
        releaseStalledHandler.countDown()
        server?.stop(0)
    }

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/chat") { exchange -> handler(exchange) }
        created.executor = java.util.concurrent.Executors.newCachedThreadPool()
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}"
    }

    private fun collect(url: String, idleLimit: Duration = Duration.ofSeconds(5)): List<ChatEvent> {
        val received = mutableListOf<ChatEvent>()
        LlmChatStreamClient(ObjectMapper(), url, idleLimit)
            .stream("req-1", listOf(mapOf("role" to "user", "content" to "hi"))) { received += it }
        return received
    }

    private fun HttpExchange.sendLines(status: Int, vararg lines: String) {
        sendResponseHeaders(status, 0)
        responseBody.use { body -> lines.forEach { body.write((it + "\n").toByteArray()); body.flush() } }
    }

    @Test
    fun `이벤트 줄을 순서대로 옮기고 요청은 ndjson을 요구한다`() {
        var acceptHeader: String? = null
        val url = startServer { exchange ->
            acceptHeader = exchange.requestHeaders.getFirst("Accept")
            exchange.sendLines(200, """{"type":"queued","position":1}""", """{"type":"start"}""",
                """{"type":"token","text":"가"}""", """{"type":"done"}""")
        }
        assertEquals(listOf(ChatEvent.Queued(1), ChatEvent.Start, ChatEvent.Token("가"), ChatEvent.Done),
            collect(url))
        assertEquals("application/x-ndjson", acceptHeader)
    }

    @Test
    fun `연결이 안 되면 LlmUnreachable - 서버 꺼짐으로 구별`() {
        val closedPort = ServerSocket(0).use { it.localPort }      // 아무도 듣지 않는 포트
        assertThrows(LlmUnreachable::class.java) { collect("http://127.0.0.1:$closedPort") }
    }

    @Test
    fun `끝 표시 없이 끊기면 upstream 오류로 끝난다 - 조용한 절단 없음`() {
        val url = startServer { exchange ->
            exchange.sendLines(200, """{"type":"start"}""", """{"type":"token","text":"반쯤"}""")
        }
        assertEquals(listOf(ChatEvent.Start, ChatEvent.Token("반쯤"), ChatEvent.Error("upstream")),
            collect(url))
    }

    @Test
    fun `200이 아니면 upstream 오류 이벤트`() {
        val url = startServer { exchange -> exchange.sendLines(503, """{"success":false}""") }
        assertEquals(listOf(ChatEvent.Error("upstream")), collect(url))
    }

    @Test
    fun `본문이 유휴 상한 동안 멈추면 기다리지 않고 upstream 오류로 끝난다`() {
        val url = startServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("""{"type":"start"}""".toByteArray() + '\n'.code.toByte())
            exchange.responseBody.flush()
            releaseStalledHandler.await(10, TimeUnit.SECONDS)      // 연결은 유지한 채 침묵
        }
        val startedAt = System.nanoTime()
        val received = collect(url, idleLimit = Duration.ofMillis(300))
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(listOf(ChatEvent.Start, ChatEvent.Error("upstream")), received)
        assertTrue(elapsedMillis < 5_000, "유휴 감시가 스트림을 닫아야 한다 (걸린 시간 ${elapsedMillis}ms)")
    }

    @Test
    fun `종료 이벤트 뒤에 온 줄은 전달하지 않는다`() {
        val url = startServer { exchange ->
            exchange.sendLines(200, """{"type":"error","code":"queue_full"}""", """{"type":"token","text":"늦은 줄"}""")
        }
        assertEquals(listOf(ChatEvent.Error("queue_full")), collect(url))
    }

    @Test
    fun `연결은 받았지만 응답을 시작하지 않으면 LlmUnreachable`() {
        val url = startServer { releaseStalledHandler.await(10, TimeUnit.SECONDS) }   // 헤더를 보내지 않는다
        val client = LlmChatStreamClient(ObjectMapper(), url, Duration.ofSeconds(5),
            headerTimeout = Duration.ofMillis(300))
        assertThrows(LlmUnreachable::class.java) {
            client.stream("req-1", listOf(mapOf("role" to "user", "content" to "hi"))) {}
        }
    }

    @Test
    fun `연결 직후 끊기면 LlmUnreachable`() {
        ServerSocket(0).use { listener ->
            val closer = Thread { runCatching { listener.accept().close() } }.apply { isDaemon = true; start() }
            assertThrows(LlmUnreachable::class.java) { collect("http://127.0.0.1:${listener.localPort}") }
            closer.join(1_000)
        }
    }

    @Test
    fun `호출부가 읽는 스레드를 깨우면 기다리지 않고 끝난다 - 떠난 브라우저의 슬롯 반납 경로`() {
        val url = startServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("""{"type":"start"}""".toByteArray() + '\n'.code.toByte())
            exchange.responseBody.flush()
            releaseStalledHandler.await(10, TimeUnit.SECONDS)
        }
        val received = java.util.concurrent.CopyOnWriteArrayList<ChatEvent>()
        val firstEventArrived = CountDownLatch(1)
        val reader = Thread {
            LlmChatStreamClient(ObjectMapper(), url, Duration.ofSeconds(30))
                .stream("req-1", listOf(mapOf("role" to "user", "content" to "hi"))) {
                    received += it
                    firstEventArrived.countDown()
                }
        }
        reader.isDaemon = true                 // 단언이 먼저 실패해도 스레드가 테스트 JVM을 붙잡지 않게
        reader.start()
        try {
            assertTrue(firstEventArrived.await(3, TimeUnit.SECONDS), "start 이벤트가 오지 않았다")
        } finally {
            reader.interrupt()
            reader.join(3_000)
        }
        assertFalse(reader.isAlive, "중단 신호 후에도 llm 스트림을 붙잡고 있다")
        assertEquals(listOf(ChatEvent.Start, ChatEvent.Error("upstream")), received)
    }

    // ---- llm 교체 중 대기열 인계: retry 를 받으면 같은 질문을 다시 보낸다 ----

    private fun startSequenceServer(vararg responses: Array<String>): Pair<String, java.util.concurrent.atomic.AtomicInteger> {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val url = startServer { exchange ->
            exchange.requestBody.readAllBytes()
            val index = calls.getAndIncrement().coerceAtMost(responses.size - 1)
            exchange.sendLines(200, *responses[index])
        }
        return url to calls
    }

    @Test
    fun `시작 전 retry 를 받으면 다시 보내고 화면에는 retry 를 내보내지 않는다`() {
        val (url, calls) = startSequenceServer(
            arrayOf("""{"type":"queued","position":1}""", """{"type":"retry"}"""),
            arrayOf("""{"type":"queued","position":2}""", """{"type":"start"}""",
                """{"type":"token","text":"가"}""", """{"type":"done"}"""))
        assertEquals(
            listOf(ChatEvent.Queued(1), ChatEvent.Queued(2), ChatEvent.Start, ChatEvent.Token("가"), ChatEvent.Done),
            collect(url))
        assertEquals(2, calls.get())
    }

    @Test
    fun `다시 보내기는 두 번까지 - 세 번째 retry 는 upstream 오류로 끝난다`() {
        val (url, calls) = startSequenceServer(arrayOf("""{"type":"retry"}"""))
        assertEquals(listOf(ChatEvent.Error("upstream")), collect(url))
        assertEquals(3, calls.get())       // 처음 1회 + 다시 보내기 2회
    }

    @Test
    fun `생성이 시작된 뒤의 retry 는 다시 보내지 않고 오류로 끝낸다`() {
        val (url, calls) = startSequenceServer(
            arrayOf("""{"type":"start"}""", """{"type":"token","text":"반쯤"}""", """{"type":"retry"}"""))
        assertEquals(listOf(ChatEvent.Start, ChatEvent.Token("반쯤"), ChatEvent.Error("upstream")), collect(url))
        assertEquals(1, calls.get())
    }

    @Test
    fun `다시 보낸 요청에서 오류가 나면 그 오류로 끝난다`() {
        val (url, calls) = startSequenceServer(
            arrayOf("""{"type":"retry"}"""),
            arrayOf("""{"type":"error","code":"queue_full"}"""))
        assertEquals(listOf(ChatEvent.Error("queue_full")), collect(url))
        assertEquals(2, calls.get())
    }

    @Test
    fun `다시 보낸 요청이 연결 단계에서 끊기면 한 번 더 보낸다 - 교체 중인 쪽이 연결을 닫는 경우`() {
        // 가짜 llm: 첫 연결은 retry 를 주고 닫는다. 두 번째 연결은 요청을 받자마자 응답 없이 끊는다(닫히는 연결을 재사용한 상황).
        // 세 번째 연결에서 정상 응답한다.
        val listener = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val connections = java.util.concurrent.atomic.AtomicInteger(0)
        val serverThread = Thread {
            try {
                while (true) {
                    val socket = listener.accept()
                    val number = connections.incrementAndGet()
                    socket.use { accepted ->
                        val reader = accepted.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.lowercase().startsWith("content-length:")) contentLength = header.substringAfter(":").trim().toInt()
                        }
                        repeat(contentLength) { reader.read() }
                        val output = accepted.getOutputStream()
                        when (number) {
                            1 -> output.write(httpResponse("""{"type":"retry"}""" + "\n"))
                            2 -> accepted.setSoLinger(true, 0)          // 응답 없이 리셋
                            else -> output.write(httpResponse("""{"type":"start"}""" + "\n" + """{"type":"done"}""" + "\n"))
                        }
                        output.flush()
                    }
                }
            } catch (_: java.io.IOException) { /* 테스트 종료 */ }
        }.apply { isDaemon = true; start() }
        try {
            val received = mutableListOf<ChatEvent>()
            LlmChatStreamClient(ObjectMapper(), "http://127.0.0.1:${listener.localPort}", Duration.ofSeconds(5),
                resendPause = Duration.ofMillis(10))
                .stream("req-1", listOf(mapOf("role" to "user", "content" to "hi"))) { received += it }
            assertEquals(listOf(ChatEvent.Start, ChatEvent.Done), received)
            assertEquals(3, connections.get())
        } finally {
            listener.close()
            serverThread.join(1000)
        }
    }

    private fun httpResponse(body: String): ByteArray {
        val bytes = body.toByteArray()
        return ("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes
    }

    @Test
    fun `처음 보내는 요청의 연결 실패는 한 번 더 보내지 않고 서버 꺼짐으로 본다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        assertThrows(LlmUnreachable::class.java) { collect("http://127.0.0.1:$closedPort") }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
    }
}
