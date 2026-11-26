package xyz.junproject.backend.chat.usecase

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Duration

/**
 * 채팅 오케스트레이션 (chat-design):
 * 대화 상태의 정본 = Redis(chat:{session}:{path}, TTL 7일) · 문서의 정본 = git(경로만 받음)
 * 컨텍스트 = 문서 원문(system) + 최근 이력 N턴 + 새 질문
 */
@Service
class ChatService(
    private val source: NoteSourcePort,
    private val chatStream: ChatStreamPort,
    private val escalate: EscalatePort,
    private val redis: StringRedisTemplate,
    private val requestLog: RequestLog,
    private val objectMapper: ObjectMapper,
    /** 넘김의 마감(요청을 받은 때부터 170초) · 단추의 기한(135초)을 재는 시계 — 테스트가 바꾼다 */
    private val clock: java.time.Clock,
) {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(source: NoteSourcePort, chatStream: ChatStreamPort, escalate: EscalatePort, redis: StringRedisTemplate,
                requestLog: RequestLog, objectMapper: ObjectMapper) :
        this(source, chatStream, escalate, redis, requestLog, objectMapper, java.time.Clock.systemUTC())

    companion object {
        const val HISTORY_TURNS = 10          // 모델에 싣는 최근 항목 수 [구현 검증]
        const val DOC_CONTEXT_CHARS = 12_000  // 문서 컨텍스트 상한 [구현 검증]
        val TTL: Duration = Duration.ofDays(7)
        /** 자동 넘김의 마감 — 요청을 받은 때부터(명세 §20.3) */
        val AUTO_ESCALATION_DEADLINE: Duration = Duration.ofSeconds(170)
        /** 마감까지 이보다 적게 남았으면 넘기지 않는다 */
        val AUTO_ESCALATION_MINIMUM: Duration = Duration.ofSeconds(20)
        /** 단추의 기한(명세 §20.4) */
        val BUTTON_DEADLINE: Duration = Duration.ofSeconds(135)
        /** 프롬프트에 싣는 문서의 상한 · 프롬프트 전체의 상한(실행 자리의 상한 — 명세 §20.2 · §21-6) */
        const val PROMPT_DOCUMENT_CHARS = 40_000
        const val PROMPT_CHARS = 60_000
        /** 문서를 잘랐을 때 붙이는 한 줄 */
        const val DOCUMENT_CUT_NOTICE = "…(문서가 길어 앞부분만 실었다)"

        /** 대화 이력의 키 `chat:{session}:{문서 경로}` — 키 꼴은 여기 한 곳(이력 지우기의 [historyKeyPattern] 도 이것을 쓴다) */
        private const val HISTORY_KEY_PREFIX = "chat:"
        /** `chat_session` 값의 꼴(UUID 의 글자) — 꼴이 틀린 값은 세션으로 쓰지 않는다 */
        private val SESSION_ID = Regex("^[0-9a-f-]{36}$")

        fun isSessionId(value: String): Boolean = SESSION_ID.matches(value)

        fun historyKey(sessionId: String, docPath: String) = "$HISTORY_KEY_PREFIX$sessionId:$docPath"

        /** 그 세션의 이력 키 전부를 찾는 SCAN 패턴 — 꼴이 맞는 세션만 받는다(꼴의 글자에는 패턴 글자 `*` `?` `[` 가 없다) */
        fun historyKeyPattern(sessionId: String): String {
            require(isSessionId(sessionId)) { "not a chat session id" }
            return "$HISTORY_KEY_PREFIX$sessionId:*"
        }
    }

    private fun key(sessionId: String, docPath: String) = historyKey(sessionId, docPath)

    /** 이력의 항목 — [source] 는 Claude 의 답에만 `"claude"`(로컬의 답 · 질문 · 옛 항목에는 칸이 없다) */
    data class ChatMessage(val role: String, val content: String, val source: String? = null)

    fun history(sessionId: String, docPath: String): List<ChatMessage> =
        redis.opsForList().range(key(sessionId, docPath), 0, -1).orEmpty()
            .map { objectMapper.readValue(it, ChatMessage::class.java) }

    private fun append(sessionId: String, docPath: String, message: ChatMessage) {
        val redisKey = key(sessionId, docPath)
        redis.opsForList().rightPush(redisKey, objectMapper.writeValueAsString(message))
        redis.expire(redisKey, TTL)           // 대화가 이어지는 동안 수명 연장
    }

    private fun buildMessages(docPath: String, historyItems: List<ChatMessage>, question: String):
            List<Map<String, String>> {
        val document = try { source.readFile(docPath).take(DOC_CONTEXT_CHARS) } catch (_: Exception) { "" }
        val system = "너는 공부 노트 위키의 문서 도우미다. 아래 문서를 근거로 한국어로 답한다. " +
            "문서에 없는 내용은 추측하지 말고 문서에 없다고 말한다.\n\n[문서: $docPath]\n$document"
        return buildList {
            add(mapOf("role" to "system", "content" to system))
            historyItems.takeLast(HISTORY_TURNS).forEach {
                add(mapOf("role" to it.role, "content" to it.content))
            }
            add(mapOf("role" to "user", "content" to question))
        }
    }

    /** 스트리밍 응답 — llm 이벤트를 onEvent로 그대로 흘린다.
     * 이력은 답변이 정상 완료(Done)된 경우에만 남긴다 — 대기 중 이탈·오류는 기록하지 않는다. */
    /**
     * [owner]: 컨트롤러가 스트림 전에 판정한 주인 여부. [receivedAt]: 요청을 받은 시각(넘김의 마감은 여기서 170초).
     * 로컬 모델의 첫 시도가 연결 단계에서 실패했고([LlmUnreachable.neverConnected]) 주인이며 자동 넘김을 할 수 있고 20초 이상 남았으면
     * Claude 로 넘긴다 — escalated → token → (truncated) → done, 거절이면 error(claude_*). 그 밖에는 LlmUnreachable 을 그대로 던진다
     */
    fun chat(requestId: String, sessionId: String, docPath: String, question: String,
             owner: Boolean = false, receivedAt: java.time.Instant? = null,
             onEvent: (ChatEvent) -> Unit) {
        val messages = buildMessages(docPath, history(sessionId, docPath), question)
        val answer = StringBuilder()
        var completed = false
        var failure: String? = null
        try {
            chatStream.stream(requestId, messages) { event ->
                when (event) {
                    is ChatEvent.Token -> answer.append(event.text)
                    is ChatEvent.Done -> completed = true
                    is ChatEvent.Error -> failure = event.code
                    is ChatEvent.Queued, is ChatEvent.Start -> Unit   // 대기 로그는 대기열 소유자(llm)가 남긴다
                    is ChatEvent.Escalated, is ChatEvent.Truncated -> Unit   // 로컬 모델은 내지 않는다(Claude 로 넘길 때 backend 가 낸다)
                }
                onEvent(event)
            }
        } finally {
            // 완료 기준은 llm의 done — 마지막 전달이 실패(브라우저 이탈)해도 완성된 답은 남긴다
            if (completed) {
                append(sessionId, docPath, ChatMessage("user", question))
                append(sessionId, docPath, ChatMessage("assistant", answer.toString()))
                requestLog.log(requestId, "chat ok $docPath q=${question.take(30)} answer=${answer.length}chars")
            }
        }
        if (!completed) {
            requestLog.log(requestId, "chat ended without answer: ${failure ?: "no terminal event"}", "warning")
        }
    }

    /** 단추 — 주인 판정 · 검증은 컨트롤러가 한 뒤. 기한 135초. 답이면 이력 두 줄(Claude 의 답에 source) */
    fun escalateAnswer(requestId: String, sessionId: String, docPath: String, question: String): EscalateResult = TODO("5a")

    private fun escalateAnswerOld(requestId: String, sessionId: String, docPath: String, question: String): String {
        check(escalate.available) { "bridge unavailable" }
        val document = try { source.readFile(docPath) } catch (_: Exception) { "" }
        val historyText = history(sessionId, docPath).takeLast(HISTORY_TURNS)
            .joinToString("\n") { "${it.role}: ${it.content.take(500)}" }
        val prompt = "다음 공부 노트 문서에 대한 질문에 정확하고 간결하게 한국어로 답해줘.\n" +
            "[문서: $docPath]\n$document\n\n[이전 대화]\n$historyText\n\n[질문]\n$question"
        val answer = (escalate.ask(requestId, prompt, clock.instant().plus(BUTTON_DEADLINE)) as EscalateResult.Answered).text
        append(sessionId, docPath, ChatMessage("user", question))
        append(sessionId, docPath, ChatMessage("assistant", answer))
        requestLog.log(requestId, "chat escalate ok $docPath answer=${answer.length}chars")
        return answer
    }
}
