package xyz.junproject.backend.shared.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 처리하지 못한 예외의 로그도 그 요청의 추적 ID 를 쓴다(통신 규약 §1.2 · §1.4 #6) — 새로 만들지 않는다.
 * - 컨트롤러가 `acceptOrIssue` 로 정한 ID(들어온 값이 꼴에 맞으면 그 값, 아니면 새로 만든 값)와 오류 로그의 ID 가 같다 — 들어온 값이 없거나 틀려
 *   컨트롤러가 새로 만든 경우에도.
 * - 컨트롤러가 ID 를 정하기 전에 터진 예외는 들어온 `X-Request-Id`(꼴이 맞을 때)를 쓰고, 없거나 틀리면 새로 만든다.
 * - 앞 요청의 ID 가 다음 요청의 오류 로그로 새지 않는다.
 * - 오류 응답의 꼴은 그대로(500 · `{"success":false,"error":{"code":"internal","detail":<예외의 종류>}}`).
 * 컨트롤러는 이 테스트만의 것이다(실제 [RequestLog] — 로그는 [LogCapture] 로 모은다).
 */
class UnhandledErrorRequestIdTest {
    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()

    @RestController
    class FailingController(private val requestLog: RequestLog) {
        /** 관례대로 ID 를 정해 한 줄 남긴 뒤 터진다 */
        @GetMapping("/test/fails-after-id")
        fun failsAfterId(@RequestHeader("X-Request-Id", required = false) incomingId: String?): String {
            val requestId = requestLog.acceptOrIssue(incomingId)
            requestLog.log(requestId, "before failure")
            throw IllegalStateException("boom")
        }

        /** ID 를 정하기 전에 터진다 */
        @GetMapping("/test/fails-before-id")
        fun failsBeforeId(): String = throw IllegalStateException("early boom")
    }

    private val mockMvc = MockMvcBuilders.standaloneSetup(FailingController(logs.requestLog))
        .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    private fun perform(request: MockHttpServletRequestBuilder): MockHttpServletResponse = mockMvc.perform(request).andReturn().response

    /** 로그 한 줄의 추적 ID(`<ID>:backend:<내용>` 의 앞) */
    private fun idOf(line: String): String = line.substringAfter(' ').substringBefore(":backend:")

    private fun unhandledLines() = logs.lines.filter { it.contains(":backend:unhandled:") }
    private fun beforeFailureLines() = logs.lines.filter { it.contains(":backend:before failure") }

    private fun assertEnvelope(response: MockHttpServletResponse) {
        assertEquals(500, response.status, response.contentAsString)
        assertEquals("""{"success":false,"error":{"code":"internal","detail":"IllegalStateException"}}""", response.contentAsString)
    }

    @Test
    fun `들어온 ID 가 꼴에 맞으면 컨트롤러와 오류 로그가 그 값을 쓴다`() {
        assertEnvelope(perform(get("/test/fails-after-id").header("X-Request-Id", "req-trace-incoming-1")))
        assertEquals("req-trace-incoming-1", idOf(beforeFailureLines().single()))
        assertEquals("req-trace-incoming-1", idOf(unhandledLines().single()), "오류 로그가 새 ID 를 만들었다")
    }

    @Test
    fun `들어온 ID 가 없어 컨트롤러가 새로 만든 경우에도 오류 로그가 같은 값을 쓴다`() {
        assertEnvelope(perform(get("/test/fails-after-id")))
        val controllerId = idOf(beforeFailureLines().single())
        assertEquals(controllerId, idOf(unhandledLines().single()), "오류 로그가 컨트롤러와 다른 ID 를 썼다")
    }

    @Test
    fun `들어온 ID 가 꼴에 맞지 않아 컨트롤러가 새로 만든 경우에도 오류 로그가 같은 값을 쓰고 틀린 값은 남지 않는다`() {
        listOf("req:trace", "a".repeat(65), "req trace", "요청").forEach { incoming ->
            logs.close()
            val fresh = LogCapture()
            val mvc = MockMvcBuilders.standaloneSetup(FailingController(fresh.requestLog)).setControllerAdvice(GlobalErrorHandler(fresh.requestLog)).build()
            assertEnvelope(mvc.perform(get("/test/fails-after-id").header("X-Request-Id", incoming)).andReturn().response)
            val controllerId = idOf(fresh.lines.single { it.contains(":backend:before failure") })
            val errorLine = fresh.lines.single { it.contains(":backend:unhandled:") }
            assertEquals(controllerId, idOf(errorLine), "오류 로그가 컨트롤러와 다른 ID 를 썼다($incoming)")
            assertNotEquals(incoming, idOf(errorLine))
            assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(idOf(errorLine)), errorLine)
            fresh.close()
        }
    }

    @Test
    fun `ID 를 정하기 전에 터지면 꼴에 맞는 들어온 ID 를 쓰고 없거나 틀리면 새로 만든다`() {
        assertEnvelope(perform(get("/test/fails-before-id").header("X-Request-Id", "req-trace-early-1")))
        assertEquals("req-trace-early-1", idOf(unhandledLines().single()))

        assertEnvelope(perform(get("/test/fails-before-id").header("X-Request-Id", "bad:id")))
        assertEnvelope(perform(get("/test/fails-before-id")))
        val others = unhandledLines().drop(1).map(::idOf)
        assertEquals(2, others.size)
        others.forEach { id ->
            assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(id), id)
            assertNotEquals("bad:id", id)
            assertNotEquals("req-trace-early-1", id, "앞 요청의 ID 가 새었다")
        }
    }

    @Test
    fun `앞 요청이 정한 ID 가 다음 요청의 오류 로그로 새지 않는다`() {
        assertEnvelope(perform(get("/test/fails-after-id").header("X-Request-Id", "req-trace-first")))
        assertEnvelope(perform(get("/test/fails-before-id")))
        val ids = unhandledLines().map(::idOf)
        assertEquals("req-trace-first", ids[0])
        assertNotEquals("req-trace-first", ids[1], "앞 요청의 ID 가 다음 요청의 오류 로그에 쓰였다")
    }
}
