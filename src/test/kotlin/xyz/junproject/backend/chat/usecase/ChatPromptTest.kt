package xyz.junproject.backend.chat.usecase

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalateFixtures.DOC_PATH
import xyz.junproject.backend.chat.usecase.EscalateFixtures.SESSION
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.progress.LogCapture
import java.net.ConnectException
import java.time.Instant

/**
 * Claude 에 보내는 프롬프트(명세 §20.2 · §21-6) — 단추(`escalateAnswer`)와 자동 넘김이 같은 꼴을 쓴다.
 * 꼴: `다음 공부 노트 문서에 대한 질문에 정확하고 간결하게 한국어로 답해줘.` LF `[문서: <경로>]` LF `<문서>` LF LF `[이전 대화]` LF `<이력>` LF LF `[질문]` LF `<질문>`.
 * - 문서는 40,000자까지 — 넘으면 앞부분을 싣고 LF 다음 `…(문서가 길어 앞부분만 실었다)` 한 줄. 문서의 몫은 min(40,000, 60,000 − 나머지 − 그 한 줄)이다.
 * - 이력은 최근 10항목, 항목마다 `<역할>: <내용의 앞 500자>` 한 줄.
 * - 다 만든 글은 60,000자를 넘지 않는다 — 넘을 수밖에 없으면(나머지만으로 넘침) 부르지 않고 rejected.
 * - 문서를 읽지 못하면 부르지 않는다(단추는 unavailable).
 */
class ChatPromptTest {
    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()

    private val clock = MovableClock(Instant.parse("2026-11-28T03:00:00Z"))
    private val lead = "다음 공부 노트 문서에 대한 질문에 정확하고 간결하게 한국어로 답해줘."
    private val cutNotice = "…(문서가 길어 앞부분만 실었다)"

    private fun historyJson(count: Int, contentLength: Int): List<String> = (1..count).map { number ->
        val role = if (number % 2 == 1) "user" else "assistant"
        val content = "H$number-" + "가".repeat(contentLength)
        ObjectMapper().writeValueAsString(mapOf("role" to role, "content" to content))
    }

    private fun promptFor(document: String, docPath: String = DOC_PATH, question: String = "질문", history: List<String> = emptyList()): Pair<String?, EscalateResult> {
        val escalate = FakeEscalate()
        val source = mockk<NoteSourcePort>().also { every { it.readFile(any()) } returns document }
        val service = ChatService(source, FakeStream {}, escalate, EscalateFixtures.FakeRedis(history).redis, logs.requestLog, EscalateFixtures.objectMapper, clock)
        val result = service.escalateAnswer("req-prompt-1", SESSION, docPath, question)
        return escalate.prompts.singleOrNull() to result
    }

    @Test
    fun `작은 글은 정해진 꼴 그대로다`() {
        val history = listOf(
            ObjectMapper().writeValueAsString(mapOf("role" to "user", "content" to "앞 질문")),
            ObjectMapper().writeValueAsString(mapOf("role" to "assistant", "content" to "앞 답", "source" to "claude")),
        )
        val (prompt, _) = promptFor("# 정리\n본문", question = "새 질문", history = history)
        assertEquals("$lead\n[문서: $DOC_PATH]\n# 정리\n본문\n\n[이전 대화]\nuser: 앞 질문\nassistant: 앞 답\n\n[질문]\n새 질문", prompt)
    }

    @Test
    fun `문서는 40000자까지 싣고 넘으면 자르고 한 줄을 붙인다`() {
        val exact = "a".repeat(40_000)
        val (exactPrompt, _) = promptFor(exact)
        assertTrue(exactPrompt!!.contains("\n$exact\n\n[이전 대화]"), "40000자의 문서를 그대로 싣지 않았다")
        assertFalse(exactPrompt.contains(cutNotice))

        val long = "b".repeat(40_000) + "c".repeat(10)
        val (longPrompt, _) = promptFor(long)
        assertTrue(longPrompt!!.contains("\n" + "b".repeat(40_000) + "\n$cutNotice\n\n[이전 대화]"), "잘라 싣고 한 줄을 붙이지 않았다")
        assertFalse(longPrompt.contains("b".repeat(40_000) + "c"), "40000자를 넘는 문서를 실었다")
    }

    @Test
    fun `이력은 최근 10항목을 항목마다 500자까지 싣는다`() {
        val (prompt, _) = promptFor("doc", history = historyJson(12, 600))
        val historyPart = prompt!!.substringAfter("[이전 대화]\n").substringBefore("\n\n[질문]")
        val lines = historyPart.lines()
        assertEquals(10, lines.size, historyPart.take(200))
        assertTrue(lines.first().startsWith("user: H3-"), lines.first().take(20))
        assertTrue(lines.last().startsWith("assistant: H12-"), lines.last().take(20))
        lines.forEach { line -> assertEquals(500, line.substringAfter(": ").length, line.take(20)) }
    }

    @Test
    fun `긴 경로와 긴 문서와 가득 찬 이력과 2000자 질문에서도 60000자를 넘지 않는다`() {
        val longPath = "cs/" + "a".repeat(1_020) + "/2-summary.md"
        val (prompt, _) = promptFor("d".repeat(100_000), docPath = longPath, question = "q".repeat(2_000), history = historyJson(10, 1_000))
        assertNotNull(prompt, "부르지 않았다")
        assertTrue(prompt!!.length <= 60_000, "프롬프트가 60000자를 넘는다: ${prompt.length}")
        assertTrue(prompt.contains(cutNotice))
        assertTrue(prompt.endsWith("[질문]\n" + "q".repeat(2_000)), "질문을 잘랐다")
    }

    @Test
    fun `나머지가 길면 문서의 몫이 줄어 60000자 안에 들고 나머지만으로 넘치면 부르지 않고 rejected 다`() {
        val hugePath = "cs/" + "p".repeat(30_000) + ".md"
        val (prompt, _) = promptFor("d".repeat(100_000), docPath = hugePath, question = "q".repeat(2_000), history = historyJson(10, 1_000))
        assertTrue(prompt != null && prompt.length <= 60_000, "프롬프트: ${prompt?.length}")
        assertTrue(prompt!!.contains(cutNotice))

        val impossiblePath = "cs/" + "p".repeat(61_000) + ".md"
        val (none, result) = promptFor("doc", docPath = impossiblePath)
        assertNull(none, "60000자를 넘는 프롬프트로 불렀다")
        assertEquals(EscalateRefusal.REJECTED, (result as? EscalateResult.Refused)?.reason, result.toString())
    }

    @Test
    fun `문서를 읽지 못하면 부르지 않고 unavailable 이다`() {
        val escalate = FakeEscalate()
        val source = mockk<NoteSourcePort>().also { every { it.readFile(any()) } throws java.nio.file.NoSuchFileException(DOC_PATH) }
        val result = ChatService(source, FakeStream {}, escalate, EscalateFixtures.FakeRedis().redis, logs.requestLog, EscalateFixtures.objectMapper, clock)
            .escalateAnswer("req-prompt-1", SESSION, DOC_PATH, "질문")
        assertEquals(0, escalate.calls.get())
        assertEquals(EscalateRefusal.UNAVAILABLE, (result as? EscalateResult.Refused)?.reason, result.toString())
    }

    @Test
    fun `자동 넘김도 같은 꼴의 프롬프트를 보낸다`() {
        val escalate = FakeEscalate()
        val source = mockk<NoteSourcePort>().also { every { it.readFile(any()) } returns "# 정리\n본문" }
        ChatService(source, FakeStream { throw LlmUnreachable(ConnectException("refused"), neverConnected = true) }, escalate,
            EscalateFixtures.FakeRedis().redis, logs.requestLog, EscalateFixtures.objectMapper, clock)
            .chat("req-prompt-2", SESSION, DOC_PATH, "새 질문", true, clock.now) {}
        assertEquals("$lead\n[문서: $DOC_PATH]\n# 정리\n본문\n\n[이전 대화]\n\n\n[질문]\n새 질문", escalate.prompts.single())
    }
}
