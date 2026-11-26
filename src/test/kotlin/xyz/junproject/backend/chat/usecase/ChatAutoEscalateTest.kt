package xyz.junproject.backend.chat.usecase

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalateFixtures.ANSWER
import xyz.junproject.backend.chat.usecase.EscalateFixtures.DOC_PATH
import xyz.junproject.backend.chat.usecase.EscalateFixtures.HISTORY_KEY
import xyz.junproject.backend.chat.usecase.EscalateFixtures.QUESTION
import xyz.junproject.backend.chat.usecase.EscalateFixtures.SESSION
import xyz.junproject.backend.progress.LogCapture
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.time.Instant

/**
 * 자동 넘김(명세 §20.3 · §21-1 · 3 · 4 · 8 · 9) — `ChatService.chat` 에 가짜 로컬 모델 · 가짜 Claude 쪽 · 가짜 Redis · 옮기는 시계.
 * - 넘기는 것은 **주인 + 첫 시도의 연결 단계 실패([LlmUnreachable.neverConnected]) + 자동 넘김을 할 수 있는 곳(실행 자리) + 마감까지 20초 이상**뿐 —
 *   그 밖의 모든 칸에서 Claude 쪽 호출 0 이고 지금 그대로(LlmUnreachable 을 던지거나 로컬의 이벤트를 그대로 흘린다).
 * - 넘길 때의 이벤트: escalated → token(답 한 번) → truncated(잘렸을 때만) → done. 거절이면 escalated → error(claude_busy · claude_limit ·
 *   claude_disabled · claude_timeout · claude_unavailable — rejected 도 claude_unavailable).
 * - 마감: 요청을 받은 때부터 170초가 Claude 쪽에 주는 기한(절대 시각). 남은 시간이 20초 미만이면 넘기지 않는다. 한 질문에 호출은 많아야 한 번.
 * - 이력: 답을 받았을 때만 질문 · 답 두 줄을 **한 명령으로**(로컬의 답도) · Claude 의 답에만 `source:"claude"` · 거절 · 실패 · 기다리다 끊김은 0줄 ·
 *   답을 받은 뒤 보내다 끊긴 것은 두 줄.
 * - 로그: `claude run: result=<낱말> auto=true duration_ms=<수>`(rejected 면 `code=` · 잘렸으면 `truncated=true`) · 질문 · 답 · 문서의 표식 글자가
 *   어느 로그에도 없다(로컬의 정상 흐름에서도 — 질문의 앞 30자를 남기던 완료 로그 포함).
 */
class ChatAutoEscalateTest {
    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()

    private val now: Instant = Instant.parse("2026-11-28T03:00:00Z")
    private val clock = MovableClock(now)
    private val objectMapper = ObjectMapper()

    private fun service(stream: FakeStream, escalate: FakeEscalate, redis: EscalateFixtures.FakeRedis = EscalateFixtures.FakeRedis()) =
        ChatService(EscalateFixtures.source(), stream, escalate, redis.redis, logs.requestLog, EscalateFixtures.objectMapper, clock)

    /** 실행 자리 · 없음 · 옛 통로만 */
    private fun runner() = FakeEscalate(available = true, autoEscalation = true)
    private fun nowhere() = FakeEscalate(available = false, autoEscalation = false)
    private fun bridgeOnly() = FakeEscalate(available = true, autoEscalation = false)

    /** 로컬 모델의 실패 — 이름 · 넘김의 조건인가 · 하는 일 */
    private data class LocalFailure(val name: String, val escalates: Boolean, val behave: (onEvent: (ChatEvent) -> Unit) -> Unit)

    private val failures = listOf(
        LocalFailure("첫 시도의 연결 거부", true) { throw LlmUnreachable(ConnectException("Connection refused"), neverConnected = true) },
        LocalFailure("첫 시도의 연결 시간 초과", true) { throw LlmUnreachable(HttpConnectTimeoutException("HTTP connect timed out"), neverConnected = true) },
        LocalFailure("주소를 풀지 못함", true) { throw LlmUnreachable(UnresolvedAddressException(), neverConnected = true) },
        LocalFailure("헤더 시간 초과", false) { throw LlmUnreachable(HttpTimeoutException("request timed out"), neverConnected = false) },
        LocalFailure("연결 직후 끊김", false) { throw LlmUnreachable(IOException("HTTP/1.1 header parser received no bytes"), neverConnected = false) },
        LocalFailure("교체 중 다시 보낸 요청의 실패", false) { throw LlmUnreachable(ConnectException("Connection refused"), neverConnected = false) },
        LocalFailure("줄이 가득 참", false) { onEvent -> onEvent(ChatEvent.Queued(9)); onEvent(ChatEvent.Error("queue_full")) },
        LocalFailure("줄에서 기다리다 시간 초과", false) { onEvent -> onEvent(ChatEvent.Queued(1)); onEvent(ChatEvent.Error("queue_timeout")) },
        LocalFailure("교체 중 upstream", false) { onEvent -> onEvent(ChatEvent.Error("upstream")) },
        LocalFailure("답이 도중에 끊김", false) { onEvent -> onEvent(ChatEvent.Start); onEvent(ChatEvent.Token("반쯤")); onEvent(ChatEvent.Error("upstream")) },
    )

    /** 한 칸을 돌린다 — 받은 이벤트와 던진 것 */
    private fun run(service: ChatService, owner: Boolean, receivedAt: Instant = now): Pair<List<ChatEvent>, Throwable?> {
        val events = mutableListOf<ChatEvent>()
        val thrown = try { service.chat("req-auto-1", SESSION, DOC_PATH, QUESTION, owner, receivedAt) { events += it }; null } catch (error: Throwable) { error }
        return events to thrown
    }

    // ---- 조건의 표 ----

    @Test
    fun `넘기는 것은 주인과 첫 시도의 연결 단계 실패와 실행 자리가 모두 맞을 때뿐이다`() {
        val wrong = mutableListOf<String>()
        for (owner in listOf(true, false)) for (failure in failures) for ((place, escalate) in listOf("실행 자리" to runner(), "없음" to nowhere(), "옛 통로만" to bridgeOnly())) {
            val stream = FakeStream(failure.behave)
            val (events, thrown) = run(service(stream, escalate), owner)
            val shouldEscalate = owner && failure.escalates && place == "실행 자리"
            val cell = "주인=$owner · ${failure.name} · $place"
            if (escalate.calls.get() != (if (shouldEscalate) 1 else 0)) wrong += "$cell: Claude 쪽 호출 ${escalate.calls.get()}"
            if (shouldEscalate) {
                if (events != listOf(ChatEvent.Escalated, ChatEvent.Token(ANSWER), ChatEvent.Done)) wrong += "$cell: 이벤트 $events"
                if (thrown != null) wrong += "$cell: 던졌다 $thrown"
            } else if (!(thrown is LlmUnreachable || events.lastOrNull() is ChatEvent.Error)) {
                wrong += "$cell: 지금 그대로가 아니다 — 이벤트 $events · 던진 것 $thrown"
            } else if (events.any { it is ChatEvent.Escalated }) {
                wrong += "$cell: 넘기지 않는데 escalated 를 냈다"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `넘기지 않는 연결 실패는 LlmUnreachable 을 그대로 던지고 로컬의 오류 이벤트는 그대로 흐른다`() {
        val (events, thrown) = run(service(FakeStream(failures[3].behave), runner()), owner = true)
        assertTrue(thrown is LlmUnreachable, "헤더 시간 초과가 llm_unreachable 로 끝나지 않는다: $thrown")
        assertEquals(emptyList<ChatEvent>(), events)
        val (queueEvents, queueThrown) = run(service(FakeStream(failures[6].behave), runner()), owner = true)
        assertNull(queueThrown)
        assertEquals(listOf(ChatEvent.Queued(9), ChatEvent.Error("queue_full")), queueEvents)
    }

    @Test
    fun `로컬 모델이 살아 있으면 Claude 쪽을 부르지 않고 이벤트가 지금과 같다`() {
        val local = listOf(ChatEvent.Queued(1), ChatEvent.Start, ChatEvent.Token("가"), ChatEvent.Token("나"), ChatEvent.Done)
        listOf(true, false).forEach { owner ->
            val escalate = runner()
            val (events, thrown) = run(service(FakeStream { onEvent -> local.forEach(onEvent) }, escalate), owner)
            assertNull(thrown)
            assertEquals(local, events)
            assertEquals(0, escalate.calls.get())
        }
    }

    // ---- 넘길 때의 이벤트 ----

    private val unreachable = failures[0]

    @Test
    fun `잘린 답이면 token 뒤 done 앞에 truncated 가 온다`() {
        val escalate = runner().also { it.answer = { EscalateResult.Answered(ANSWER, true) } }
        val (events, _) = run(service(FakeStream(unreachable.behave), escalate), owner = true)
        assertEquals(listOf(ChatEvent.Escalated, ChatEvent.Token(ANSWER), ChatEvent.Truncated, ChatEvent.Done), events)
    }

    @Test
    fun `거절의 낱말마다 escalated 뒤에 정해진 오류 코드다`() {
        val expected = mapOf(
            EscalateRefusal.BUSY to "claude_busy", EscalateRefusal.LIMIT_REACHED to "claude_limit", EscalateRefusal.DISABLED to "claude_disabled",
            EscalateRefusal.TIMEOUT to "claude_timeout", EscalateRefusal.UNAVAILABLE to "claude_unavailable", EscalateRefusal.REJECTED to "claude_unavailable",
        )
        expected.forEach { (reason, code) ->
            val escalate = runner().also { it.answer = { EscalateResult.Refused(reason, if (reason == EscalateRefusal.REJECTED) "prompt_too_long" else null) } }
            val (events, thrown) = run(service(FakeStream(unreachable.behave), escalate), owner = true)
            assertNull(thrown, "$reason: $thrown")
            assertEquals(listOf(ChatEvent.Escalated, ChatEvent.Error(code)), events, "$reason")
            assertEquals(1, escalate.calls.get(), "$reason: 한 질문에 호출은 한 번")
        }
    }

    @Test
    fun `문서를 읽지 못하면 Claude 쪽을 부르지 않고 claude_unavailable 이다`() {
        val escalate = runner()
        val source = io.mockk.mockk<xyz.junproject.backend.content.usecase.NoteSourcePort>().also {
            io.mockk.every { it.readFile(any()) } throws java.nio.file.NoSuchFileException(DOC_PATH)
        }
        val service = ChatService(source, FakeStream(unreachable.behave), escalate, EscalateFixtures.FakeRedis().redis, logs.requestLog, EscalateFixtures.objectMapper, clock)
        val (events, thrown) = run(service, owner = true)
        assertNull(thrown)
        assertEquals(0, escalate.calls.get())
        assertEquals(ChatEvent.Error("claude_unavailable"), events.last())
    }

    // ---- 마감 ----

    @Test
    fun `Claude 쪽에 주는 기한은 요청을 받은 때부터 170초의 절대 시각이다`() {
        val escalate = runner()
        val receivedAt = now.minusSeconds(3)
        run(service(FakeStream(unreachable.behave), escalate), owner = true, receivedAt = receivedAt)
        assertEquals(listOf(receivedAt.plusSeconds(170)), escalate.deadlines)
    }

    @Test
    fun `마감까지 20초 이상 남아야 넘기고 미만이면 넘기지 않는다`() {
        val exactlyTwenty = runner()
        run(service(FakeStream(unreachable.behave), exactlyTwenty), owner = true, receivedAt = now.minusSeconds(150))
        assertEquals(1, exactlyTwenty.calls.get(), "20초가 남았는데 넘기지 않았다")

        val lessThanTwenty = runner()
        val (events, thrown) = run(service(FakeStream(unreachable.behave), lessThanTwenty), owner = true, receivedAt = now.minusSeconds(150).minusMillis(1))
        assertEquals(0, lessThanTwenty.calls.get(), "20초 미만인데 넘겼다")
        assertTrue(thrown is LlmUnreachable, "넘기지 않으면 llm_unreachable 로 끝나야 한다: $thrown · $events")
    }

    @Test
    fun `로컬 모델을 다시 부르지 않고 Claude 쪽도 한 번만 부른다`() {
        val stream = FakeStream(unreachable.behave)
        val escalate = runner().also { it.answer = { EscalateResult.Refused(EscalateRefusal.UNAVAILABLE) } }
        run(service(stream, escalate), owner = true)
        assertEquals(1, stream.calls.get())
        assertEquals(1, escalate.calls.get())
    }

    // ---- 이력 ----

    private fun json(text: String): Map<*, *> = objectMapper.readValue(text, Map::class.java)

    @Test
    fun `Claude 의 답을 받으면 질문과 답 두 줄을 한 명령으로 쓰고 답에만 source claude 가 있다`() {
        val redis = EscalateFixtures.FakeRedis()
        service(FakeStream(unreachable.behave), runner(), redis).chat("req-auto-1", SESSION, DOC_PATH, QUESTION, true, now) {}
        val command = redis.commands.single()
        assertEquals("rightPushAll", command.name, "두 줄을 한 명령으로 쓰지 않았다: ${redis.commands}")
        assertEquals(HISTORY_KEY, command.key)
        assertEquals(2, command.values.size)
        val (question, answer) = command.values.map(::json)
        assertEquals(mapOf("role" to "user", "content" to QUESTION), question)
        assertEquals(mapOf("role" to "assistant", "content" to ANSWER, "source" to "claude"), answer)
    }

    @Test
    fun `로컬의 답도 두 줄을 한 명령으로 쓰고 source 칸이 없다`() {
        val redis = EscalateFixtures.FakeRedis()
        val stream = FakeStream { onEvent -> listOf(ChatEvent.Start, ChatEvent.Token("로컬"), ChatEvent.Token("답"), ChatEvent.Done).forEach(onEvent) }
        service(stream, runner(), redis).chat("req-local-1", SESSION, DOC_PATH, QUESTION, true, now) {}
        val command = redis.commands.single()
        assertEquals("rightPushAll", command.name, "${redis.commands}")
        assertEquals(listOf(mapOf("role" to "user", "content" to QUESTION), mapOf("role" to "assistant", "content" to "로컬답")), command.values.map(::json))
        assertTrue(command.values.none { it.contains("source") }, "로컬의 답에 source 칸이 있다: ${command.values}")
    }

    @Test
    fun `거절과 실패에는 이력을 쓰지 않는다`() {
        EscalateRefusal.entries.forEach { reason ->
            val redis = EscalateFixtures.FakeRedis()
            val escalate = runner().also { it.answer = { EscalateResult.Refused(reason) } }
            run(service(FakeStream(unreachable.behave), escalate, redis), owner = true)
            assertEquals(emptyList<EscalateFixtures.ListCommand>(), redis.commands, "$reason")
        }
    }

    @Test
    fun `Claude 를 기다리다 끊기면 이력을 쓰지 않고 끊김이 올라온다`() {
        val redis = EscalateFixtures.FakeRedis()
        val escalate = runner().also { it.answer = { throw InterruptedException("client gone") } }
        val (events, thrown) = run(service(FakeStream(unreachable.behave), escalate, redis), owner = true)
        assertTrue(thrown is InterruptedException, "끊김을 삼켰다: $thrown · $events")
        assertEquals(emptyList<EscalateFixtures.ListCommand>(), redis.commands)
        assertFalse(events.any { it is ChatEvent.Token || it is ChatEvent.Done }, events.toString())
    }

    @Test
    fun `답을 받은 뒤 보내다 끊기면 이력은 두 줄 쓴다`() {
        val redis = EscalateFixtures.FakeRedis()
        val thrown = assertThrows(IllegalStateException::class.java) {
            service(FakeStream(unreachable.behave), runner(), redis).chat("req-auto-1", SESSION, DOC_PATH, QUESTION, true, now) { event ->
                if (event is ChatEvent.Token) throw IllegalStateException("client gone")
            }
        }
        assertEquals("client gone", thrown.message)
        assertEquals(listOf("rightPushAll"), redis.commands.map { it.name })
        assertEquals(2, redis.commands.single().values.size)
    }

    @Test
    fun `옛 이력 항목은 source 없이 그대로 읽힌다`() {
        val redis = EscalateFixtures.FakeRedis(listOf(
            """{"role":"user","content":"옛 질문"}""", """{"role":"assistant","content":"옛 답"}""",
            """{"role":"assistant","content":"Claude 답","source":"claude"}""",
        ))
        val history = service(FakeStream {}, runner(), redis).history(SESSION, DOC_PATH)
        assertEquals(listOf(
            ChatService.ChatMessage("user", "옛 질문"), ChatService.ChatMessage("assistant", "옛 답"),
            ChatService.ChatMessage("assistant", "Claude 답", "claude"),
        ), history)
    }

    // ---- 로그 ----

    private fun runLine(requestId: String) = logs.lines.filter { it.contains("$requestId:") && it.contains("claude run:") }

    @Test
    fun `넘김의 결과가 한 줄로 남는다`() {
        val cases = listOf(
            Triple(EscalateResult.Answered(ANSWER, false), "result=answered", null),
            Triple(EscalateResult.Answered(ANSWER, true), "result=answered", "truncated=true"),
            Triple(EscalateResult.Refused(EscalateRefusal.BUSY), "result=busy", null),
            Triple(EscalateResult.Refused(EscalateRefusal.LIMIT_REACHED), "result=limit_reached", null),
            Triple(EscalateResult.Refused(EscalateRefusal.DISABLED), "result=disabled", null),
            Triple(EscalateResult.Refused(EscalateRefusal.TIMEOUT), "result=timeout", null),
            Triple(EscalateResult.Refused(EscalateRefusal.UNAVAILABLE), "result=unavailable", null),
            Triple(EscalateResult.Refused(EscalateRefusal.REJECTED, "prompt_too_long"), "result=rejected", "code=prompt_too_long"),
        )
        cases.forEachIndexed { index, (result, word, extra) ->
            val requestId = "req-run-$index"
            ChatService(EscalateFixtures.source(), FakeStream(unreachable.behave), runner().also { it.answer = { result } },
                EscalateFixtures.FakeRedis().redis, logs.requestLog, EscalateFixtures.objectMapper, clock)
                .chat(requestId, SESSION, DOC_PATH, QUESTION, true, now) {}
            val lines = runLine(requestId)
            assertEquals(1, lines.size, "$result: ${logs.lines}")
            val line = lines.single()
            assertTrue(line.contains(word) && line.contains("auto=true") && Regex("duration_ms=\\d+").containsMatchIn(line), line)
            if (extra != null) assertTrue(line.contains(extra), line)
            if (result is EscalateResult.Refused && result.reason == EscalateRefusal.REJECTED) assertTrue(line.startsWith("ERROR"), "rejected 는 error 로그: $line")
        }
    }

    @Test
    fun `질문 답 문서의 표식 글자가 어느 로그에도 없다 - 로컬의 정상 흐름과 넘김 모두`() {
        val local = FakeStream { onEvent -> listOf(ChatEvent.Start, ChatEvent.Token(ANSWER), ChatEvent.Done).forEach(onEvent) }
        service(local, runner()).chat("req-local-2", SESSION, DOC_PATH, QUESTION, true, now) {}
        service(FakeStream(unreachable.behave), runner()).chat("req-auto-2", SESSION, DOC_PATH, QUESTION, true, now) {}
        val escalateRejected = runner().also { it.answer = { EscalateResult.Refused(EscalateRefusal.REJECTED, "prompt_too_long") } }
        service(FakeStream(unreachable.behave), escalateRejected).chat("req-auto-3", SESSION, DOC_PATH, QUESTION, true, now) {}
        assertTrue(logs.lines.any { it.contains("req-local-2:") }, "준비: 로컬 흐름의 로그가 없다 ${logs.lines}")
        val leaked = logs.lines.filter { line -> listOf("QUESTION-MARKER", "ANSWER-MARKER", "DOCUMENT-MARKER").any { line.contains(it) } }
        assertEquals(emptyList<String>(), leaked, "로그에 내용이 있다")
    }

    // ---- 지금의 규칙 ----

    @Test
    fun `넘김을 기다리는 시간은 주어진 시계로 잰다`() {
        val escalate = runner().also { it.answer = { clock.now = clock.now.plus(Duration.ofSeconds(42)); EscalateResult.Answered(ANSWER, false) } }
        service(FakeStream(unreachable.behave), escalate).chat("req-auto-4", SESSION, DOC_PATH, QUESTION, true, now) {}
        assertTrue(runLine("req-auto-4").single().contains("duration_ms=42000"), logs.lines.toString())
    }
}
