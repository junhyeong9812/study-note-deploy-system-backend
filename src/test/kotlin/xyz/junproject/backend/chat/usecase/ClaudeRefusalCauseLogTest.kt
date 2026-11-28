package xyz.junproject.backend.chat.usecase

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalateFixtures.DOC_PATH
import xyz.junproject.backend.chat.usecase.EscalateFixtures.QUESTION
import xyz.junproject.backend.chat.usecase.EscalateFixtures.SESSION
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.infra.ClaudeRunnerClient
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 실행 자리가 "못 씀"으로 끝났을 때 그 까닭이 backend 의 로그 한 줄에서 갈린다 — 실제 [ChatService] · 실제 [ClaudeRunnerClient] · 가짜 실행 자리(JDK `HttpServer`).
 * - `result=unavailable` 의 줄은 그 뒤에 ` status=<상태 코드>` 를, 실행 자리의 오류 낱말이 알려진 목록에 있으면 ` code=<낱말>` 을 덧붙인다.
 *   목록에 없는 낱말 · 낱말이 없는 몸은 `status=` 만(실행 자리의 글자는 알려진 낱말일 때만 줄에 들어간다). 연결하지 못하면 상태 코드가 없어 줄은 그대로다.
 * - 앞의 글자(`claude run: result=unavailable auto=… duration_ms=…`)는 바뀌지 않는다 — 관측 서버의 알림 규칙이 `claude run: result=unavailable` 을 찾는다.
 * - 화면으로 가는 오류 코드(`claude_unavailable`)와 다른 결과의 줄은 그대로다.
 * 시계를 멈춰 두어 `duration_ms=0` — 줄을 글자 그대로 견준다.
 */
class ClaudeRefusalCauseLogTest {
    private val logs = LogCapture()
    private var server: HttpServer? = null

    @AfterEach
    fun close() {
        server?.stop(0)
        logs.close()
    }

    /** [status] 와 [body] 로 답하는 가짜 실행 자리의 주소 */
    private fun runnerAnswering(status: Int, body: String): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).also { created ->
            created.createContext("/runs") { exchange ->
                exchange.requestBody.readAllBytes()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            created.start()
        }
        return "http://127.0.0.1:${server!!.address.port}"
    }

    private fun failure(code: String) = ObjectMapper().writeValueAsString(mapOf("success" to false, "error" to mapOf("code" to code)))

    /** 자동 넘김 한 번 — 받은 이벤트와 그 요청의 `claude run:` 줄 */
    private fun escalateThrough(runnerUrl: String, requestId: String): Pair<List<ChatEvent>, String> {
        val stoppedClock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
        val service = ChatService(EscalateFixtures.source(), FakeStream { throw LlmUnreachable(ConnectException("refused"), neverConnected = true) },
            ClaudeRunnerClient(runnerUrl, "runner-secret-for-cause-test", ObjectMapper()), EscalateFixtures.FakeRedis().redis,
            logs.requestLog, EscalateFixtures.objectMapper, stoppedClock)
        val events = mutableListOf<ChatEvent>()
        service.chat(requestId, SESSION, DOC_PATH, QUESTION, true, stoppedClock.instant()) { events += it }
        val line = logs.lines.single { it.contains("$requestId:") && it.contains("claude run:") }
        return events to line
    }

    private fun assertUnavailableLine(runnerUrl: String, requestId: String, expectedLine: String) {
        val (events, line) = escalateThrough(runnerUrl, requestId)
        assertEquals(expectedLine, line)
        assertEquals(listOf(ChatEvent.Escalated, ChatEvent.Error("claude_unavailable")), events, "화면으로 가는 오류 코드가 바뀌었다")
    }

    @Test
    fun `502 claude_failed 는 상태와 낱말을 덧붙인다`() {
        assertUnavailableLine(runnerAnswering(502, failure("claude_failed")), "req-cause-1",
            "WARN req-cause-1:backend:claude run: result=unavailable auto=true duration_ms=0 status=502 code=claude_failed")
    }

    @Test
    fun `503 token_missing 은 상태와 낱말을 덧붙인다`() {
        assertUnavailableLine(runnerAnswering(503, failure("token_missing")), "req-cause-2",
            "WARN req-cause-2:backend:claude run: result=unavailable auto=true duration_ms=0 status=503 code=token_missing")
    }

    @Test
    fun `503 ledger_unavailable 은 상태와 낱말을 덧붙인다`() {
        assertUnavailableLine(runnerAnswering(503, failure("ledger_unavailable")), "req-cause-3",
            "WARN req-cause-3:backend:claude run: result=unavailable auto=true duration_ms=0 status=503 code=ledger_unavailable")
    }

    @Test
    fun `낱말이 없는 500 은 상태만 덧붙인다`() {
        assertUnavailableLine(runnerAnswering(500, "Internal Server Error"), "req-cause-4",
            "WARN req-cause-4:backend:claude run: result=unavailable auto=true duration_ms=0 status=500")
    }

    @Test
    fun `목록에 없는 낱말은 한 글자도 싣지 않고 상태만 덧붙인다`() {
        assertUnavailableLine(runnerAnswering(502, failure("weird\nINJECT")), "req-cause-5",
            "WARN req-cause-5:backend:claude run: result=unavailable auto=true duration_ms=0 status=502")
        assertTrue(logs.lines.none { it.contains("INJECT") || it.contains("weird") }, logs.lines.toString())
    }

    @Test
    fun `200 인데 몸이 이상하면 상태 200 만 덧붙인다`() {
        assertUnavailableLine(runnerAnswering(200, """{"success":true,"data":{"text":""}}"""), "req-cause-6",
            "WARN req-cause-6:backend:claude run: result=unavailable auto=true duration_ms=0 status=200")
    }

    @Test
    fun `닿지 않으면 상태 코드가 없어 줄은 그대로다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertUnavailableLine("http://127.0.0.1:$closedPort", "req-cause-7",
            "WARN req-cause-7:backend:claude run: result=unavailable auto=true duration_ms=0")
    }

    @Test
    fun `덧붙여도 앞의 글자 claude run result=unavailable 은 그대로 들어 있다`() {
        val (_, line) = escalateThrough(runnerAnswering(502, failure("claude_failed")), "req-cause-8")
        assertTrue(line.contains("req-cause-8:backend:claude run: result=unavailable auto=true duration_ms="), line)
    }

    @Test
    fun `다른 결과의 줄은 그대로다`() {
        val (_, rejected) = escalateThrough(runnerAnswering(401, failure("unauthorized")), "req-cause-9")
        assertEquals("ERROR req-cause-9:backend:claude run: result=rejected auto=true duration_ms=0 code=unauthorized", rejected)
        val (_, busy) = escalateThrough(runnerAnswering(429, failure("busy")), "req-cause-10")
        assertEquals("WARN req-cause-10:backend:claude run: result=busy auto=true duration_ms=0", busy)
        val (_, answered) = escalateThrough(runnerAnswering(200, """{"success":true,"data":{"text":"답"}}"""), "req-cause-11")
        assertEquals("INFO req-cause-11:backend:claude run: result=answered auto=true duration_ms=0", answered)
    }
}
