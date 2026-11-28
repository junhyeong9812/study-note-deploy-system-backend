package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalateRefusal
import xyz.junproject.backend.chat.usecase.EscalateResult
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 시간 초과 · 끊김에서 호출의 취소가 **연결을 실제로 닫는다**(명세 §22 ②) — 결과만 돌려주고 연결을 열어 둔 채 남기지 않는다.
 * 가짜 실행 자리는 소켓을 직접 다룬다: 요청을 다 읽은 뒤 [stall] 대로 머리글이나 몸을 미루고, 그동안 상대의 쪽에서 읽어 EOF · 리셋이 오면
 * "상대가 닫았다"를 그 시각과 함께 남긴다. 기한 · 끊김 뒤 [closeAllowance](2초) 안에 닫힘이 남아야 한다(폴링으로 기다린다).
 * 시간 초과의 두 테스트는 **연결이 열리고 요청이 도착한 뒤에 기한이 온다**를 스스로 보장한다 — 클래스의 처음에 같은 경로의 호출을 한 번 해
 * JVM · 클라이언트를 데우고, 기한을 [timeoutDeadline](1.5초)로 넉넉히 주며(가짜는 닫힐 때까지 미룬다), 단언의 앞에서 "가짜가 기한 전에
 * 이 호출의 요청을 받았다"를 먼저 본다 — 받지 못했으면 테스트의 전제가 깨진 것으로 실패한다(조용히 통과하지 않는다).
 */
class ClaudeRunnerCancelTest {
    private val closeAllowance: Duration = Duration.ofSeconds(2)
    private val timeoutDeadline: Duration = Duration.ofMillis(1_500)

    companion object {
        /** 같은 경로(연결 · 보내기 · 받기)를 한 번 지나 JVM 과 HTTP 클라이언트의 처음 비용을 치른다 — 데워지지 않으면 첫 호출이 소켓에 닿기 전에 기한을 다 쓴다 */
        @JvmStatic
        @BeforeAll
        fun warmUp() {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 4)
            server.createContext("/runs") { exchange ->
                exchange.requestBody.readAllBytes()
                val bytes = """{"success":true,"data":{"text":"warm","truncated":false}}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            server.start()
            try {
                repeat(2) {
                    ClaudeRunnerClient("http://127.0.0.1:${server.address.port}", "runner-secret-for-cancel-test", ObjectMapper())
                        .ask("req-cancel-warm", "prompt", Instant.now().plusSeconds(10))
                }
            } finally {
                server.stop(0)
            }
        }
    }
    private val servers = mutableListOf<ServerSocket>()

    @AfterEach
    fun close() = servers.forEach { runCatching { it.close() } }

    private enum class Stall { HEADERS, BODY }

    /** 요청 하나를 받는 가짜 — 받았을 때 [received] 가 풀리고, 상대가 닫으면 [closedAt] 에 시각이 남는다 */
    private inner class StallingRunner(private val stall: Stall) {
        val server = ServerSocket(0).also { servers += it }
        val received = CountDownLatch(1)
        val receivedAt = AtomicReference<Instant?>(null)
        val closedAt = AtomicReference<Instant?>(null)
        val url get() = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { connection -> serve(connection) }
                }
            }
        }

        private fun readRequest(input: InputStream) {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return
                head.append(next.toChar())
            }
            val length = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            repeat(length) { input.read() }
        }

        private fun serve(connection: Socket) {
            val input = connection.getInputStream()
            readRequest(input)
            receivedAt.set(Instant.now())
            received.countDown()
            if (stall == Stall.BODY) {
                connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{".toByteArray())
                connection.getOutputStream().flush()
            }
            // 아무것도 더 보내지 않고 상대의 쪽을 읽는다 — EOF(-1)나 리셋이면 상대가 닫은 것
            try {
                while (input.read() >= 0) { /* 더 오는 바이트는 버린다 */ }
            } catch (_: Exception) {
            }
            closedAt.compareAndSet(null, Instant.now())
        }
    }

    private fun client(url: String) = ClaudeRunnerClient(url, "runner-secret-for-cancel-test", ObjectMapper())

    /** 전제: 가짜가 기한 전에 이 호출의 요청을 다 받았다 — 연결이 열린 뒤에 기한이 왔다 */
    private fun assertReceivedBefore(runner: StallingRunner, deadline: Instant, what: String) {
        val receivedAt = runner.receivedAt.get()
        assertNotNull(receivedAt, "$what: 기한 전에 연결이 열리지 않았다 — 테스트의 전제가 깨졌다")
        assertTrue(receivedAt!!.isBefore(deadline), "$what: 요청이 기한 뒤에야 도착했다($receivedAt > $deadline) — 테스트의 전제가 깨졌다")
    }

    private fun assertClosedWithin(runner: StallingRunner, since: Instant, what: String) {
        val deadline = System.nanoTime() + closeAllowance.toNanos()
        while (runner.closedAt.get() == null && System.nanoTime() < deadline) Thread.sleep(20)
        val closedAt = runner.closedAt.get()
        assertNotNull(closedAt, "$what: ${closeAllowance.toMillis()}ms 안에 연결을 닫지 않았다")
        assertTrue(!closedAt!!.isBefore(since.minusMillis(50)), "$what: 기한 · 끊김 전에 닫혔다 — 준비가 틀렸다")
        assertFalse(closedAt.isAfter(since.plus(closeAllowance)), "$what: 기한 · 끊김에서 ${closeAllowance.toMillis()}ms 가 지난 뒤에야 닫혔다($closedAt)")
    }

    @Test
    fun `머리글이 늦어 시간 초과로 끝나면 연결을 닫는다`() {
        val runner = StallingRunner(Stall.HEADERS)
        val deadline = Instant.now().plus(timeoutDeadline)
        val result = client(runner.url).ask("req-cancel-1", "prompt", deadline)
        assertReceivedBefore(runner, deadline, "머리글이 늦은 시간 초과")
        assertEquals(EscalateRefusal.TIMEOUT, (result as? EscalateResult.Refused)?.reason, result.toString())
        assertClosedWithin(runner, deadline, "머리글이 늦은 시간 초과")
    }

    @Test
    fun `몸이 늦어 시간 초과로 끝나면 연결을 닫는다`() {
        val runner = StallingRunner(Stall.BODY)
        val deadline = Instant.now().plus(timeoutDeadline)
        val result = client(runner.url).ask("req-cancel-2", "prompt", deadline)
        assertReceivedBefore(runner, deadline, "몸이 늦은 시간 초과")
        assertEquals(EscalateRefusal.TIMEOUT, (result as? EscalateResult.Refused)?.reason, result.toString())
        assertClosedWithin(runner, deadline, "몸이 늦은 시간 초과")
    }

    private fun interruptCloses(virtual: Boolean) {
        val runner = StallingRunner(Stall.HEADERS)
        val outcome = AtomicReference<Any?>()
        val work = Runnable {
            outcome.set(try { client(runner.url).ask("req-cancel-3", "prompt", Instant.now().plusSeconds(30)) } catch (error: Throwable) { error })
        }
        val worker = if (virtual) Thread.ofVirtual().start(work) else Thread(work).also { it.start() }
        assertTrue(runner.received.await(5, TimeUnit.SECONDS), "준비: 가짜가 요청을 받지 못했다")
        val interruptedAt = Instant.now()
        worker.interrupt()
        worker.join(5_000)
        assertTrue(outcome.get() is InterruptedException, "끊김이 올라오지 않았다: ${outcome.get()}")
        assertClosedWithin(runner, interruptedAt, if (virtual) "가상 스레드의 끊김" else "플랫폼 스레드의 끊김")
    }

    @Test
    fun `플랫폼 스레드가 끊기면 연결을 닫는다`() = interruptCloses(virtual = false)

    @Test
    fun `가상 스레드가 끊기면 연결을 닫는다`() = interruptCloses(virtual = true)
}
