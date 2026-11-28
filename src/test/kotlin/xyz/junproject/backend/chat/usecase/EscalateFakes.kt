package xyz.junproject.backend.chat.usecase

import io.mockk.every
import io.mockk.mockk
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import xyz.junproject.backend.content.usecase.NoteSourcePort
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** 손으로 옮기는 시계 */
class MovableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** 가짜 Claude 쪽 — 부른 수 · 받은 프롬프트 · 기한을 남기고 [answer] 를 돌려준다(던지면 그대로 던진다) */
class FakeEscalate(
    override val available: Boolean = true,
    override val autoEscalation: Boolean = true,
    var answer: (prompt: String) -> EscalateResult = { EscalateResult.Answered(EscalateFixtures.ANSWER, false) },
) : EscalatePort {
    val calls = AtomicInteger()
    val prompts = mutableListOf<String>()
    val deadlines = mutableListOf<Instant>()
    override fun ask(requestId: String, prompt: String, deadline: Instant): EscalateResult {
        calls.incrementAndGet()
        prompts += prompt
        deadlines += deadline
        return answer(prompt)
    }
}

/** 가짜 로컬 모델 — [behave] 가 이벤트를 내거나 던진다 */
class FakeStream(var behave: (onEvent: (ChatEvent) -> Unit) -> Unit) : ChatStreamPort {
    val calls = AtomicInteger()
    override fun stream(requestId: String, messages: List<Map<String, String>>, onEvent: (ChatEvent) -> Unit) {
        calls.incrementAndGet()
        behave(onEvent)
    }
}

object EscalateFixtures {
    /** 채팅의 이력을 읽고 쓰는 데 쓰는 매퍼 — 앱처럼 코틀린 모듈을 단다(데이터 클래스를 읽는다) */
    val objectMapper: tools.jackson.databind.ObjectMapper =
        tools.jackson.databind.json.JsonMapper.builder().addModule(tools.jackson.module.kotlin.KotlinModule.Builder().build()).build()

    const val QUESTION = "QUESTION-MARKER 이분 탐색의 경계는?"
    const val ANSWER = "ANSWER-MARKER 경계는 이렇게 잡는다"
    const val DOCUMENT_MARK = "DOCUMENT-MARKER"
    const val DOC_PATH = "cs/algorithm/06-binary-search/2-summary.md"
    const val SESSION = "11111111-2222-3333-4444-555555555555"
    val HISTORY_KEY = "chat:$SESSION:$DOC_PATH"

    /** 목록에 쓴 명령 하나 — 명령의 이름 · 키 · 값들 */
    data class ListCommand(val name: String, val key: String, val values: List<String>)

    /**
     * 가짜 Redis — 목록에 쓰는 명령(rightPush · rightPushAll · leftPush · leftPushAll)을 명령 단위로 [commands] 에 남긴다
     * (같은 명령의 다른 꼴 — 가변 인자 · 모음 — 은 같은 이름이다). [stored] 가 그 키의 지금 항목(읽기)
     */
    class FakeRedis(stored: List<String> = emptyList()) {
        val commands: MutableList<ListCommand> = java.util.Collections.synchronizedList(mutableListOf())
        private val delegate = mockk<ListOperations<String, String>>(relaxed = true).also {
            every { it.range(any(), any(), any()) } returns stored
        }
        val listOperations: ListOperations<String, String> = object : ListOperations<String, String> by delegate {
            override fun rightPush(key: String, value: String): Long? { commands += ListCommand("rightPush", key, listOf(value)); return 1 }
            override fun rightPush(key: String, pivot: String, value: String): Long? { commands += ListCommand("rightPush", key, listOf(value)); return 1 }
            override fun rightPushAll(key: String, vararg values: String): Long? { commands += ListCommand("rightPushAll", key, values.toList()); return values.size.toLong() }
            override fun rightPushAll(key: String, values: MutableCollection<String>): Long? { commands += ListCommand("rightPushAll", key, values.toList()); return values.size.toLong() }
            override fun leftPush(key: String, value: String): Long? { commands += ListCommand("leftPush", key, listOf(value)); return 1 }
            override fun leftPushAll(key: String, vararg values: String): Long? { commands += ListCommand("leftPushAll", key, values.toList()); return values.size.toLong() }
            override fun leftPushAll(key: String, values: MutableCollection<String>): Long? { commands += ListCommand("leftPushAll", key, values.toList()); return values.size.toLong() }
        }
        val redis = mockk<StringRedisTemplate>(relaxed = true).also { every { it.opsForList() } returns listOperations }
    }

    fun source(document: String = "# 정리\n$DOCUMENT_MARK 본문\n"): NoteSourcePort =
        mockk<NoteSourcePort>().also { every { it.readFile(any()) } returns document }
}
