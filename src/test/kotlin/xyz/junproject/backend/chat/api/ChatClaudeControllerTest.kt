package xyz.junproject.backend.chat.api

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.chat.usecase.EscalateFixtures
import xyz.junproject.backend.chat.usecase.EscalateRefusal
import xyz.junproject.backend.chat.usecase.EscalateResult
import xyz.junproject.backend.chat.usecase.FakeEscalate
import xyz.junproject.backend.chat.usecase.FakeStream
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import xyz.junproject.backend.chat.usecase.MovableClock
import xyz.junproject.backend.owner.api.OwnerCookie
import xyz.junproject.backend.owner.usecase.OwnerJudge
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import java.net.ConnectException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 채팅 경로에서 Claude 로 가는 두 길(명세 §20.3 · §20.4) — 실제 [ChatService] 에 가짜 로컬 모델 · 가짜 Claude 쪽 · 가짜 Redis, 실제 주인 판정.
 * - 자동 넘김: 컨트롤러가 스트림 전에 주인을 판정해 넘긴다 — 주인이 아니면 Claude 쪽 호출 0 이고 `error{llm_unreachable}` 그대로 ·
 *   주인이면 `escalated` → `token` → `done` 이 SSE 로 나간다 · 로컬이 살아 있으면 지금과 바이트까지 같다.
 * - 단추(`POST /api/chat/escalate`): 주인 판정 → 검증(질문 1~2000자) → 같은 Claude 쪽. 답 200 `{answer, truncated}` · busy 429 `claude_busy` ·
 *   limit_reached 429 `claude_limit` · disabled 429 `claude_disabled` · timeout 504 `claude_timeout` · unavailable · rejected 503 `escalate_failed` ·
 *   부를 곳이 없으면 503 `escalate_unavailable`(호출 0). 기한은 135초.
 * - 이력 읽기(`GET /api/chat/history`)는 `source` 가 있는 항목에만 그 칸을 낸다.
 */
class ChatClaudeControllerTest {
    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()

    private val ownerSecret = "0123456789abcdef0123456789abcdef"
    private val ownerMarkValue = "ow1.1764000000000.SsdDQjHHXxd2qHi5SeD58PoS0zumVJNEKlcZgUAgM54"
    private val judge = OwnerJudge(ownerSecret, Clock.fixed(Instant.ofEpochMilli(1_764_000_000_000L).plusSeconds(60), ZoneOffset.UTC), logs.requestLog)
    private val json = JsonMapper.builder().build()
    private val buttonClock = MovableClock(Instant.parse("2026-11-28T03:00:00Z"))

    private fun mockMvc(service: ChatService): MockMvc = MockMvcBuilders
        .standaloneSetup(ChatController(service, logs.requestLog, ObjectMapper(), judge))
        .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    /** SSE 는 요청을 받은 때를 실제 시각으로 잰다 — 서비스도 실제 시계 */
    private fun streamingService(stream: FakeStream, escalate: FakeEscalate, redis: EscalateFixtures.FakeRedis = EscalateFixtures.FakeRedis()) =
        ChatService(EscalateFixtures.source(), stream, escalate, redis.redis, logs.requestLog, EscalateFixtures.objectMapper, Clock.systemUTC())

    private fun buttonService(escalate: FakeEscalate, redis: EscalateFixtures.FakeRedis = EscalateFixtures.FakeRedis()) =
        ChatService(EscalateFixtures.source(), FakeStream {}, escalate, redis.redis, logs.requestLog, EscalateFixtures.objectMapper, buttonClock)

    private fun asOwner(request: MockHttpServletRequestBuilder) = request.cookie(Cookie(OwnerCookie.NAME, ownerMarkValue))

    private val body = """{"doc_path":"${EscalateFixtures.DOC_PATH}","question":"질문"}"""

    private fun streamed(mockMvc: MockMvc, owner: Boolean): String {
        val builder = post("/api/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM_VALUE).content(body)
        val result = mockMvc.perform(if (owner) asOwner(builder) else builder).andExpect(request().asyncStarted()).andReturn()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            !result.response.getContentAsString(Charsets.UTF_8).let { it.contains("event:done") || it.contains("event:error") }) Thread.sleep(20)
        Thread.sleep(50)
        return result.response.getContentAsString(Charsets.UTF_8)
    }

    private val neverConnected = FakeStream { throw LlmUnreachable(ConnectException("refused"), neverConnected = true) }

    // ---- 자동 넘김 ----

    @Test
    fun `주인의 요청은 로컬에 연결되지 못하면 escalated token done 이 SSE 로 나간다`() {
        val escalate = FakeEscalate()
        val sse = streamed(mockMvc(streamingService(neverConnected, escalate)), owner = true)
        val escalatedAt = sse.indexOf("event:escalated\ndata:{}")
        val tokenAt = sse.indexOf("event:token\ndata:{\"text\":\"${EscalateFixtures.ANSWER}\"}")
        val doneAt = sse.indexOf("event:done")
        assertTrue(escalatedAt >= 0 && escalatedAt < tokenAt && tokenAt < doneAt, sse)
        assertFalse(sse.contains("llm_unreachable"), sse)
        assertEquals(1, escalate.calls.get())
    }

    @Test
    fun `주인이 아닌 요청은 Claude 쪽을 부르지 않고 llm_unreachable 그대로다`() {
        listOf(FakeEscalate(), FakeEscalate().also { it.answer = { EscalateResult.Refused(EscalateRefusal.BUSY) } }).forEach { escalate ->
            val sse = streamed(mockMvc(streamingService(neverConnected, escalate)), owner = false)
            assertTrue(sse.contains("event:error\ndata:{\"code\":\"llm_unreachable\"}"), sse)
            assertFalse(sse.contains("escalated"), sse)
            assertEquals(0, escalate.calls.get())
        }
    }

    @Test
    fun `머리나 질의의 글자로는 주인이 되지 않아 넘기지 않는다`() {
        val escalate = FakeEscalate()
        val mockMvc = mockMvc(streamingService(neverConnected, escalate))
        val builder = post("/api/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM_VALUE).content(body)
            .header("X-Owner", "true").param("owner", "true").cookie(Cookie("study_visitor", ownerMarkValue))
        val result = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !result.response.getContentAsString(Charsets.UTF_8).contains("event:")) Thread.sleep(20)
        Thread.sleep(100)
        assertEquals(0, escalate.calls.get())
    }

    @Test
    fun `로컬이 살아 있으면 주인이어도 Claude 쪽을 부르지 않고 SSE 가 지금과 바이트까지 같다`() {
        val escalate = FakeEscalate()
        val local = FakeStream { onEvent -> listOf(ChatEvent.Queued(2), ChatEvent.Start, ChatEvent.Token("안녕\n하세요"), ChatEvent.Done).forEach(onEvent) }
        val sse = streamed(mockMvc(streamingService(local, escalate)), owner = true)
        assertEquals(
            "event:queued\ndata:{\"position\":2}\n\nevent:start\ndata:{}\n\nevent:token\ndata:{\"text\":\"안녕\\n하세요\"}\n\nevent:done\ndata:{}\n\n",
            sse,
        )
        assertEquals(0, escalate.calls.get())
    }

    @Test
    fun `옛 계약의 글자 흐름에서는 넘기지 않는다`() {
        val escalate = FakeEscalate()
        val result = asOwner(post("/api/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_PLAIN_VALUE).content(body))
            .let { mockMvc(streamingService(neverConnected, escalate)).perform(it).andReturn() }
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && result.response.getContentAsString(Charsets.UTF_8).isEmpty()) Thread.sleep(20)
        Thread.sleep(100)
        assertEquals(0, escalate.calls.get())
    }

    // ---- 단추 ----

    private fun button(service: ChatService, question: String = "질문"): MockHttpServletResponse =
        mockMvc(service).perform(asOwner(post("/api/chat/escalate").contentType(MediaType.APPLICATION_JSON)
            .content(ObjectMapper().writeValueAsString(mapOf("doc_path" to EscalateFixtures.DOC_PATH, "question" to question))))).andReturn().response

    private fun errorCode(response: MockHttpServletResponse) = (json.readValue(response.contentAsString, Map::class.java)["error"] as? Map<*, *>)?.get("code")

    @Test
    fun `단추의 답은 200 answer 와 truncated 이고 기한은 135초다`() {
        listOf(false, true).forEach { truncated ->
            val escalate = FakeEscalate().also { it.answer = { EscalateResult.Answered(EscalateFixtures.ANSWER, truncated) } }
            val response = button(buttonService(escalate))
            assertEquals(200, response.status, response.contentAsString)
            val data = json.readValue(response.contentAsString, Map::class.java)["data"] as Map<*, *>
            assertEquals(mapOf("answer" to EscalateFixtures.ANSWER, "truncated" to truncated), data)
            assertEquals(listOf(buttonClock.now.plusSeconds(135)), escalate.deadlines)
        }
    }

    @Test
    fun `단추의 거절은 낱말마다 정해진 상태와 코드다`() {
        val expected = listOf(
            EscalateRefusal.BUSY to (429 to "claude_busy"), EscalateRefusal.LIMIT_REACHED to (429 to "claude_limit"),
            EscalateRefusal.DISABLED to (429 to "claude_disabled"), EscalateRefusal.TIMEOUT to (504 to "claude_timeout"),
            EscalateRefusal.UNAVAILABLE to (503 to "escalate_failed"), EscalateRefusal.REJECTED to (503 to "escalate_failed"),
        )
        expected.forEach { (reason, statusAndCode) ->
            val response = button(buttonService(FakeEscalate().also { it.answer = { EscalateResult.Refused(reason, "some_code") } }))
            assertEquals(statusAndCode.first, response.status, "$reason: ${response.contentAsString}")
            assertEquals(statusAndCode.second, errorCode(response), "$reason")
            assertFalse(response.contentAsString.contains("some_code"), "실행 자리의 코드가 응답에 나갔다")
        }
    }

    @Test
    fun `부를 곳이 없으면 503 escalate_unavailable 이고 부르지 않는다`() {
        val escalate = FakeEscalate(available = false, autoEscalation = false)
        val response = button(buttonService(escalate))
        assertEquals(503, response.status, response.contentAsString)
        assertEquals("escalate_unavailable", errorCode(response))
        assertEquals(0, escalate.calls.get())
    }

    @Test
    fun `옛 통로만 있어도 단추는 부른다`() {
        val escalate = FakeEscalate(available = true, autoEscalation = false)
        assertEquals(200, button(buttonService(escalate)).status)
        assertEquals(1, escalate.calls.get())
    }

    @Test
    fun `단추의 질문은 1에서 2000자이고 넘거나 공백뿐이면 422 이며 부르지 않는다`() {
        val escalate = FakeEscalate()
        val service = buttonService(escalate)
        assertEquals(422, button(service, "q".repeat(2_001)).status)
        assertEquals(422, button(service, "   \n ").status)
        assertEquals(0, escalate.calls.get())
        assertEquals(200, button(service, "q".repeat(2_000)).status)
    }

    @Test
    fun `단추의 답은 이력에 한 명령으로 쓰이고 답에 source claude 가 있다`() {
        val redis = EscalateFixtures.FakeRedis()
        button(buttonService(FakeEscalate(), redis))
        val command = redis.commands.single()
        assertEquals("rightPushAll", command.name)
        assertTrue(command.values[1].contains("\"source\":\"claude\""), command.values.toString())
        assertFalse(command.values[0].contains("source"), command.values.toString())
    }

    // ---- 이력 읽기 ----

    @Test
    fun `이력 읽기는 source 가 있는 항목에만 그 칸을 낸다`() {
        val redis = EscalateFixtures.FakeRedis(listOf(
            """{"role":"user","content":"질문"}""", """{"role":"assistant","content":"로컬 답"}""",
            """{"role":"assistant","content":"Claude 답","source":"claude"}""",
        ))
        val response = mockMvc(buttonService(FakeEscalate(), redis)).perform(get("/api/chat/history").param("doc_path", EscalateFixtures.DOC_PATH)
            .cookie(Cookie("chat_session", EscalateFixtures.SESSION))).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        val messages = (json.readValue(response.contentAsString, Map::class.java)["data"] as Map<*, *>)["messages"]
        assertEquals(listOf(
            mapOf("role" to "user", "content" to "질문"),
            mapOf("role" to "assistant", "content" to "로컬 답"),
            mapOf("role" to "assistant", "content" to "Claude 답", "source" to "claude"),
        ), messages)
    }
}
