package xyz.junproject.backend.shared.infra

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.chat.usecase.EscalateRefusal
import xyz.junproject.backend.chat.usecase.EscalateResult
import xyz.junproject.backend.progress.LogCapture
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * backend 가 Claude 실행 자리를 부르는 클라이언트(명세 §20.1 · §21-2 · 5 · 7 · 9) — 실제 소켓을 여는 가짜 실행 자리(JDK `HttpServer`)로.
 * 가짜는 실행 자리의 계약대로 `Transfer-Encoding` 이 있거나 `Content-Length` 가 없으면 411 `length_required` 를 낸다.
 * - 요청: `POST /runs` · `X-Claude-Runner-Secret` · `Content-Length` 가 있는 JSON 몸 `{"purpose":"chat","prompt":…,"request_id":…}` · 칸은 그 셋뿐.
 * - 결과는 값: 200 의 답(`text` · `truncated`) 또는 거절의 낱말 — 상태 코드가 아니라 `error.code` 로 가른다. 모르는 코드 · 꼴이 틀린 답 · 연결 실패는 unavailable.
 *   401 · 400 · 411 · 413 · 422 · 404 는 rejected(backend 의 잘못) 이고 실행 자리의 코드를 싣는다.
 * - 기한은 호출 전체의 절대 마감 — 머리글이 늦거나 몸이 늦거나 몸을 조금씩 흘리며 끝내지 않아도 기한에 timeout 으로 돌아온다.
 * - 리다이렉트를 따라가지 않는다(비밀값이 다른 곳으로 가지 않는다) · 재시도 없음 · 기다리는 동안의 interrupt 는 InterruptedException 으로 올라온다.
 * - 비밀값 · 프롬프트 · 답이 결과의 글 · 예외의 글 · 로그에 없다 · 설정이 없으면 부를 곳이 없다.
 */
class ClaudeRunnerClientTest {
    private val secret = "SECRET-MARKER-runner-0123456789"
    private val prompt = "PROMPT-MARKER 문서와 질문"
    private val answer = "ANSWER-MARKER 답입니다"
    private val json = JsonMapper.builder().build()
    private val logs = LogCapture()
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun close() {
        servers.forEach { it.stop(0) }
        logs.close()
    }

    /** 받은 요청 하나 */
    data class Received(val method: String, val path: String, val headers: Map<String, List<String>>, val body: ByteArray)

    /** 가짜 실행 자리 — [respond] 가 응답을 쓴다. 계약대로 길이 없는 몸 · chunked 는 411 */
    private inner class FakeRunner(private val respond: (HttpExchange) -> Unit) {
        val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
        val calls = AtomicInteger()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).also { server ->
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                val headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }
                val chunked = headers["transfer-encoding"] != null
                val hasLength = headers["content-length"] != null
                val body = exchange.requestBody.readAllBytes()
                received += Received(exchange.requestMethod, exchange.requestURI.path, headers, body)
                try {
                    if (chunked || !hasLength) send(exchange, 411, """{"success":false,"error":{"code":"length_required"}}""")
                    else respond(exchange)
                } catch (_: Exception) {
                } finally {
                    runCatching { exchange.close() }
                }
            }
            server.start()
            servers += server
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun answering(status: Int, body: String) = FakeRunner { exchange -> send(exchange, status, body) }

    private fun client(url: String, clientSecret: String = secret) = ClaudeRunnerClient(url, clientSecret, ObjectMapper())

    private fun soon(milliseconds: Long = 5_000): Instant = Instant.now().plusMillis(milliseconds)

    private fun ask(runner: FakeRunner, deadline: Instant = soon()): EscalateResult = client(runner.url).ask("req-claude-1", prompt, deadline)

    private fun success(text: Any?, truncated: Any? = false, extra: String = "") =
        """{"success":true,"data":{${if (text == null) "" else "\"text\":${json.writeValueAsString(text)},"}"purpose":"chat","request_id":"req-claude-1","duration_ms":12,"usage":null${if (truncated == null) "" else ",\"truncated\":$truncated"}$extra}}"""

    private fun failure(code: String, extra: String = "") = """{"success":false,"error":{"code":"$code"$extra}}"""

    // ---- 요청의 꼴 ----

    @Test
    fun `요청은 POST runs 에 비밀값 머리글과 길이가 있는 JSON 몸 셋의 칸이다`() {
        val runner = answering(200, success(answer))
        assertEquals(EscalateResult.Answered(answer, false), ask(runner))

        val request = runner.received.single()
        assertEquals("POST", request.method)
        assertEquals("/runs", request.path)
        assertEquals(listOf(secret), request.headers["x-claude-runner-secret"])
        assertNull(request.headers["transfer-encoding"], "chunked 로 보냈다")
        assertEquals(listOf(request.body.size.toString()), request.headers["content-length"])
        assertTrue(request.headers["content-type"]?.single()?.startsWith("application/json") == true, request.headers.toString())
        val body = json.readValue(request.body, Map::class.java)
        assertEquals(mapOf("purpose" to "chat", "prompt" to prompt, "request_id" to "req-claude-1"), body)
    }

    @Test
    fun `한글과 줄바꿈이 든 프롬프트도 UTF-8 바이트 그대로 간다`() {
        val runner = answering(200, success(answer))
        val text = "첫 줄\n둘째 줄 \"따옴표\" \\ 끝"
        client(runner.url).ask("req-claude-2", text, soon())
        val body = json.readValue(runner.received.single().body, Map::class.java)
        assertEquals(text, body["prompt"])
    }

    // ---- 결과의 표 ----

    @Test
    fun `200 의 답은 글과 잘림을 싣는다`() {
        assertEquals(EscalateResult.Answered(answer, false), ask(answering(200, success(answer, truncated = false))))
        assertEquals(EscalateResult.Answered(answer, true), ask(answering(200, success(answer, truncated = true))))
        assertEquals(EscalateResult.Answered(answer, false), ask(answering(200, success(answer, truncated = null))), "truncated 칸이 없으면 잘리지 않은 것")
    }

    @Test
    fun `429 는 상태가 아니라 코드로 바쁨 상한 꺼짐을 가른다`() {
        assertEquals(EscalateRefusal.BUSY, (ask(answering(429, failure("busy"))) as EscalateResult.Refused).reason)
        assertEquals(EscalateRefusal.LIMIT_REACHED,
            (ask(answering(429, failure("limit_reached", ""","scope":"total","window":"hour","limit":10"""))) as EscalateResult.Refused).reason)
        assertEquals(EscalateRefusal.DISABLED, (ask(answering(429, failure("disabled"))) as EscalateResult.Refused).reason)
        assertEquals(EscalateRefusal.UNAVAILABLE, (ask(answering(429, failure("something_new"))) as EscalateResult.Refused).reason, "429 의 모르는 코드")
        assertEquals(EscalateRefusal.UNAVAILABLE, (ask(answering(429, "Too Many Requests")) as EscalateResult.Refused).reason, "429 의 JSON 이 아닌 몸")
    }

    @Test
    fun `실행 자리의 시간 초과는 timeout 이다`() {
        assertEquals(EscalateRefusal.TIMEOUT, (ask(answering(504, failure("timeout"))) as EscalateResult.Refused).reason)
    }

    @Test
    fun `503 502 500 은 unavailable 이다`() {
        listOf(
            503 to "token_missing", 503 to "claude_unavailable", 503 to "limits_unavailable", 503 to "ledger_unavailable",
            502 to "claude_failed", 502 to "bad_output", 502 to "invalid_answer", 500 to "internal",
        ).forEach { (status, code) ->
            val result = ask(answering(status, failure(code)))
            assertEquals(EscalateRefusal.UNAVAILABLE, (result as? EscalateResult.Refused)?.reason, "$status $code: $result")
        }
    }

    @Test
    fun `backend 의 잘못을 뜻하는 거절은 rejected 이고 실행 자리의 코드를 싣는다`() {
        listOf(
            401 to "unauthorized", 400 to "bad_length", 411 to "length_required", 413 to "bad_length",
            422 to "prompt_too_long", 422 to "unknown_field", 422 to "invalid_request_id", 404 to "not_found",
        ).forEach { (status, code) ->
            val result = ask(answering(status, failure(code)))
            assertEquals(EscalateResult.Refused(EscalateRefusal.REJECTED, code), result, "$status $code")
        }
    }

    @Test
    fun `모르는 상태와 꼴이 틀린 답은 unavailable 이다`() {
        val cases = mapOf(
            "모르는 상태 418" to answering(418, failure("teapot")),
            "모르는 상태 302 의 몸" to answering(302, failure("moved")),
            "JSON 이 아닌 몸" to answering(200, "<html>oops</html>"),
            "빈 몸" to answering(200, ""),
            "text 없음" to answering(200, success(null)),
            "text 가 빈 글" to answering(200, success("")),
            "text 가 공백뿐" to answering(200, success("   \n")),
            "text 가 문자열이 아님" to answering(200, success(42)),
            "text 가 객체" to answering(200, success(mapOf("a" to 1))),
            "200 인데 success 가 거짓" to answering(200, failure("busy")),
            "data 가 없음" to answering(200, """{"success":true}"""),
            "봉투가 배열" to answering(200, "[1,2]"),
        )
        cases.forEach { (what, runner) ->
            val result = ask(runner)
            assertEquals(EscalateRefusal.UNAVAILABLE, (result as? EscalateResult.Refused)?.reason, "$what: $result")
        }
    }

    @Test
    fun `연결하지 못하면 unavailable 이다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = client("http://127.0.0.1:$closedPort").ask("req-claude-1", prompt, soon())
        assertEquals(EscalateRefusal.UNAVAILABLE, (result as? EscalateResult.Refused)?.reason, result.toString())
    }

    // ---- 절대 마감 ----

    private fun assertTimesOutAround(runner: FakeRunner, deadlineMillis: Long = 400) {
        val started = System.nanoTime()
        val result = ask(runner, Instant.now().plusMillis(deadlineMillis))
        val took = Duration.ofNanos(System.nanoTime() - started).toMillis()
        assertEquals(EscalateResult.Refused(EscalateRefusal.TIMEOUT), (result as? EscalateResult.Refused)?.copy(code = null), result.toString())
        assertTrue(took >= deadlineMillis - 50, "기한 전에 끝났다: ${took}ms")
        assertTrue(took < deadlineMillis + 1_000, "기한에서 끊지 않았다: ${took}ms")
    }

    @Test
    fun `머리글이 늦으면 기한에 timeout 이다`() {
        assertTimesOutAround(FakeRunner { exchange -> Thread.sleep(10_000); send(exchange, 200, success(answer)) })
    }

    @Test
    fun `머리글은 왔는데 몸이 늦으면 기한에 timeout 이다`() {
        assertTimesOutAround(FakeRunner { exchange ->
            val bytes = success(answer).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.flush()
            Thread.sleep(10_000)
            exchange.responseBody.write(bytes)
        })
    }

    @Test
    fun `몸을 조금씩 흘리며 끝내지 않아도 기한에 timeout 이다`() {
        assertTimesOutAround(FakeRunner { exchange ->
            exchange.sendResponseHeaders(200, 0)
            val output = exchange.responseBody
            output.write("""{"success":true,"data":{"text":"""".toByteArray())
            repeat(400) { output.write("a".toByteArray()); output.flush(); Thread.sleep(50) }
        })
    }

    @Test
    fun `기한이 이미 지났으면 부르지 않고 timeout 이다`() {
        val runner = answering(200, success(answer))
        val result = ask(runner, Instant.now().minusSeconds(1))
        assertEquals(EscalateRefusal.TIMEOUT, (result as? EscalateResult.Refused)?.reason, result.toString())
        assertEquals(0, runner.calls.get())
    }

    // ---- 리다이렉트 · 재시도 · 끊김 ----

    @Test
    fun `리다이렉트를 따라가지 않고 비밀값이 다른 곳으로 가지 않는다`() {
        val elsewhere = answering(200, success(answer))
        val redirecting = FakeRunner { exchange ->
            exchange.responseHeaders.add("Location", "${elsewhere.url}/runs")
            exchange.sendResponseHeaders(302, -1)
        }
        val result = ask(redirecting)
        assertEquals(EscalateRefusal.UNAVAILABLE, (result as? EscalateResult.Refused)?.reason, result.toString())
        assertEquals(0, elsewhere.calls.get(), "리다이렉트를 따라갔다")
        assertEquals(1, redirecting.calls.get())
    }

    @Test
    fun `실패해도 다시 보내지 않는다`() {
        listOf(answering(503, failure("claude_unavailable")), answering(500, failure("internal")), answering(429, failure("busy")),
            FakeRunner { exchange -> exchange.sendResponseHeaders(200, 0); exchange.responseBody.write("{".toByteArray()) },
        ).forEach { runner ->
            ask(runner)
            assertEquals(1, runner.calls.get(), "실행 자리를 두 번 이상 불렀다")
        }
    }

    @Test
    fun `기다리는 동안 끊기면 결과로 삼키지 않고 InterruptedException 이 올라온다`() {
        val entered = CountDownLatch(1)
        val runner = FakeRunner { exchange -> entered.countDown(); Thread.sleep(10_000); send(exchange, 200, success(answer)) }
        val outcome = AtomicReference<Any?>()
        val worker = Thread {
            outcome.set(try { client(runner.url).ask("req-claude-1", prompt, soon(20_000)) } catch (error: Throwable) { error })
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "준비: 가짜가 요청을 받지 못했다")
        val interruptedAt = System.nanoTime()
        worker.interrupt()
        worker.join(5_000)
        assertFalse(worker.isAlive, "끊었는데 돌아오지 않는다")
        assertTrue(Duration.ofNanos(System.nanoTime() - interruptedAt).toMillis() < 2_000)
        val result = outcome.get()
        assertTrue(result is InterruptedException, "끊김을 삼켰다: $result")
        val text = (result as Throwable).toString() + (result.cause?.toString() ?: "")
        assertFalse(text.contains("PROMPT-MARKER") || text.contains("SECRET-MARKER"), text)
    }

    // ---- 내용이 새지 않는다 ----

    @Test
    fun `비밀값 프롬프트 답이 결과의 글과 로그에 없다`() {
        val results = listOf(
            ask(answering(200, success(answer))),
            ask(answering(401, failure("unauthorized"))),
            ask(answering(422, """{"success":false,"error":{"code":"prompt_too_long","detail":"PROMPT-MARKER echoed"}}""")),
            ask(answering(500, "ANSWER-MARKER stack trace SECRET-MARKER")),
            ask(FakeRunner { exchange -> Thread.sleep(10_000); send(exchange, 200, success(answer)) }, Instant.now().plusMillis(200)),
        )
        results.drop(1).forEach { result ->
            val text = result.toString()
            listOf("SECRET-MARKER", "PROMPT-MARKER", "ANSWER-MARKER").forEach { marker -> assertFalse(text.contains(marker), "결과의 글에 $marker: $text") }
        }
        val leaked = logs.lines.filter { line -> listOf("SECRET-MARKER", "PROMPT-MARKER", "ANSWER-MARKER").any { line.contains(it) } }
        assertEquals(emptyList<String>(), leaked, "로그에 내용이 있다")
    }

    // ---- 설정 ----

    @Test
    fun `주소나 비밀값이 비면 부를 곳이 없다`() {
        val runner = answering(200, success(answer))
        listOf(client("", secret), client("   ", secret), client(runner.url, ""), client(runner.url, "  ")).forEach { unconfigured ->
            assertFalse(unconfigured.available)
            assertFalse(unconfigured.autoEscalation)
        }
        assertEquals(0, runner.calls.get())
        val configured = client(runner.url)
        assertTrue(configured.available)
        assertTrue(configured.autoEscalation, "실행 자리는 자동 넘김을 할 수 있다")
    }

    @Test
    fun `주소 끝의 빗금이 있어도 runs 로 간다`() {
        val runner = answering(200, success(answer))
        client("${runner.url}/").ask("req-claude-1", prompt, soon())
        assertEquals("/runs", runner.received.single().path)
    }
}
