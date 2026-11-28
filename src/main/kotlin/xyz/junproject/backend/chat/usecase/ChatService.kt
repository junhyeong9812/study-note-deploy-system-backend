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
        /** 프롬프트의 첫 줄 */
        const val PROMPT_LEAD = "다음 공부 노트 문서에 대한 질문에 정확하고 간결하게 한국어로 답해줘."
        /** 이력에서 Claude 의 답을 가르는 값 */
        const val CLAUDE_SOURCE = "claude"

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
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    data class ChatMessage(val role: String, val content: String, val source: String? = null)

    fun history(sessionId: String, docPath: String): List<ChatMessage> =
        redis.opsForList().range(key(sessionId, docPath), 0, -1).orEmpty()
            .map { objectMapper.readValue(it, ChatMessage::class.java) }

    /** 질문 · 답 두 줄을 한 명령(RPUSH 에 두 값)으로 — 질문만 남는 일이 없게(명세 §21-8) */
    private fun appendExchange(sessionId: String, docPath: String, question: ChatMessage, answer: ChatMessage) {
        val redisKey = key(sessionId, docPath)
        redis.opsForList().rightPushAll(redisKey, objectMapper.writeValueAsString(question), objectMapper.writeValueAsString(answer))
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

    /**
     * 스트리밍 응답 — llm 이벤트를 onEvent로 그대로 흘린다.
     * 이력은 답변이 정상 완료(Done)된 경우에만 남긴다 — 대기 중 이탈·오류는 기록하지 않는다.
     *
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
            } catch (unreachable: LlmUnreachable) {
                val deadline = (receivedAt ?: clock.instant()).plus(AUTO_ESCALATION_DEADLINE)
                if (!canAutoEscalate(owner, unreachable, deadline)) throw unreachable
                escalateInStream(requestId, sessionId, docPath, question, deadline, onEvent)
                return
            }
        } finally {
            // 완료 기준은 llm의 done — 마지막 전달이 실패(브라우저 이탈)해도 완성된 답은 남긴다
            if (completed) {
                appendExchange(sessionId, docPath, ChatMessage("user", question), ChatMessage("assistant", answer.toString()))
                requestLog.log(requestId, "chat ok $docPath question=${question.length}chars answer=${answer.length}chars")
            }
        }
        if (!completed) {
            requestLog.log(requestId, "chat ended without answer: ${failure ?: "no terminal event"}", "warning")
        }
    }

    /** 넘김의 조건 넷 — 주인 · 첫 시도의 연결 단계 실패 · 자동 넘김을 할 수 있는 곳(실행 자리) · 마감까지 20초 이상 */
    private fun canAutoEscalate(owner: Boolean, unreachable: LlmUnreachable, deadline: java.time.Instant): Boolean =
        owner && unreachable.neverConnected && escalate.autoEscalation &&
            Duration.between(clock.instant(), deadline) >= AUTO_ESCALATION_MINIMUM

    /** 자동 넘김 — escalated → token → (truncated) → done, 거절이면 escalated → error(claude_*). Claude 의 답은 이력을 먼저 쓰고 보낸다 */
    private fun escalateInStream(requestId: String, sessionId: String, docPath: String, question: String,
                                 deadline: java.time.Instant, onEvent: (ChatEvent) -> Unit) {
        onEvent(ChatEvent.Escalated)
        when (val result = askClaude(requestId, sessionId, docPath, question, deadline, auto = true)) {
            is EscalateResult.Answered -> {
                onEvent(ChatEvent.Token(result.text))
                if (result.truncated) onEvent(ChatEvent.Truncated)
                onEvent(ChatEvent.Done)
            }
            is EscalateResult.Refused -> onEvent(ChatEvent.Error(streamCodeOf(result.reason)))
        }
    }

    /** 단추 — 주인 판정 · 검증은 컨트롤러가 한 뒤. 기한 135초. 답이면 이력 두 줄(Claude 의 답에 source). 부를 곳이 없으면 IllegalStateException */
    fun escalateAnswer(requestId: String, sessionId: String, docPath: String, question: String): EscalateResult {
        check(escalate.available) { "claude escalation unavailable" }
        return askClaude(requestId, sessionId, docPath, question, clock.instant().plus(BUTTON_DEADLINE), auto = false)
    }

    /**
     * Claude 를 한 번 부른다(단추 · 자동 넘김 공통). 문서를 읽지 못하면 부르지 않고 unavailable · 프롬프트가 상한을 넘으면 부르지 않고 rejected.
     * 답이면 질문 · 답(source=claude)을 이력에 쓴 뒤 돌려준다. 기다리는 동안 끊기면(InterruptedException) 그대로 올린다 — 이력을 쓰지 않는다
     */
    private fun askClaude(requestId: String, sessionId: String, docPath: String, question: String,
                          deadline: java.time.Instant, auto: Boolean): EscalateResult {
        val document = try {
            source.readFile(docPath)
        } catch (_: Exception) {
            requestLog.log(requestId, "claude not run: document unreadable auto=$auto", "warning")
            return EscalateResult.Refused(EscalateRefusal.UNAVAILABLE)
        }
        val prompt = promptOf(docPath, document, history(sessionId, docPath), question) ?: run {
            requestLog.log(requestId, "claude not run: prompt over $PROMPT_CHARS chars or not encodable auto=$auto", "error")
            return EscalateResult.Refused(EscalateRefusal.REJECTED)
        }
        val startedAt = clock.instant()
        val result = try {
            escalate.ask(requestId, prompt, deadline)
        } catch (gone: InterruptedException) {
            // 기다리다 끊겼다 — 실행 자리의 한도는 쓰였을 수 있다(사용량을 맞춰 볼 때 빠지지 않게 한 줄). 이력은 쓰지 않는다
            requestLog.log(requestId, "claude run: result=abandoned auto=$auto duration_ms=${Duration.between(startedAt, clock.instant()).toMillis()}", "warning")
            throw gone
        }
        val durationMillis = Duration.between(startedAt, clock.instant()).toMillis()
        if (result is EscalateResult.Answered) {
            appendExchange(sessionId, docPath, ChatMessage("user", question), ChatMessage("assistant", result.text, CLAUDE_SOURCE))
        }
        logRun(requestId, result, auto, durationMillis)
        return result
    }

    /**
     * 프롬프트 — 안내 · `[문서: 경로]` · 문서 · `[이전 대화]` · 이력 · `[질문]` · 질문. 문서를 뺀 나머지를 먼저 만들고 문서의 몫을 정한다:
     * min([PROMPT_DOCUMENT_CHARS], [PROMPT_CHARS] − 나머지 − 자름 표시). 나머지만으로 넘치면 null(부르지 않는다).
     * 실행 자리는 UTF-8 로 바꿀 수 없는 프롬프트를 거절한다 — 원래 글의 홀로 선 서로게이트는 U+FFFD 로 바꾸고, 자르는 끝은 짝 글자의 가운데를 피한다
     */
    private fun promptOf(docPath: String, document: String, historyItems: List<ChatMessage>, question: String): String? {
        val historyText = historyItems.takeLast(HISTORY_TURNS)
            .joinToString("\n") { "${encodable(it.role)}: ${takeWhole(encodable(it.content), 500)}" }
        val head = "$PROMPT_LEAD\n[문서: ${encodable(docPath)}]\n"
        val tail = "\n\n[이전 대화]\n$historyText\n\n[질문]\n${encodable(question)}"
        val cutLine = "\n$DOCUMENT_CUT_NOTICE"
        val documentShare = minOf(PROMPT_DOCUMENT_CHARS, PROMPT_CHARS - head.length - tail.length - cutLine.length)
        if (documentShare < 0) return null
        val cleanDocument = encodable(document)
        val documentPart = if (cleanDocument.length <= documentShare) cleanDocument else takeWhole(cleanDocument, documentShare) + cutLine
        return (head + documentPart + tail).takeIf { it.length <= PROMPT_CHARS && strictlyEncodable(it) }
    }

    /** 홀로 선 서로게이트(짝이 없는 반쪽)를 U+FFFD 로 — 그대로 실으면 UTF-8 로 바꿀 수 없다 */
    private fun encodable(text: String): String {
        if (text.none { Character.isSurrogate(it) }) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (Character.isHighSurrogate(character) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                builder.append(character).append(text[index + 1])
                index += 2
                continue
            }
            builder.append(if (Character.isSurrogate(character)) '\uFFFD' else character)
            index++
        }
        return builder.toString()
    }

    /** 앞에서 [limit] 글자(UTF-16)까지 — 끝이 짝 글자의 앞쪽 반이면 한 글자 덜(짝을 가르지 않는다) */
    private fun takeWhole(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (limit > 0 && Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }

    /** 엄격하게 UTF-8 로 바꿀 수 있는가 — 방어선(위의 둘이 맞으면 언제나 참) */
    private fun strictlyEncodable(text: String): Boolean = try {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        true
    } catch (_: java.nio.charset.CharacterCodingException) {
        false
    }

    /** `claude run: result=<낱말> auto=<참거짓> duration_ms=<수>`(+ truncated=true · code=) — 답은 info · rejected 는 error · 그 밖의 거절은 warning */
    private fun logRun(requestId: String, result: EscalateResult, auto: Boolean, durationMillis: Long) {
        when (result) {
            is EscalateResult.Answered ->
                requestLog.log(requestId, "claude run: result=answered auto=$auto duration_ms=$durationMillis" + if (result.truncated) " truncated=true" else "")
            is EscalateResult.Refused -> {
                val code = result.code?.let { " code=$it" } ?: ""
                val level = if (result.reason == EscalateRefusal.REJECTED) "error" else "warning"
                requestLog.log(requestId, "claude run: result=${result.reason.word} auto=$auto duration_ms=$durationMillis$code", level)
            }
        }
    }

    /** 자동 넘김의 오류 코드 — rejected 도 화면에는 claude_unavailable */
    private fun streamCodeOf(reason: EscalateRefusal): String = when (reason) {
        EscalateRefusal.BUSY -> "claude_busy"
        EscalateRefusal.LIMIT_REACHED -> "claude_limit"
        EscalateRefusal.DISABLED -> "claude_disabled"
        EscalateRefusal.TIMEOUT -> "claude_timeout"
        EscalateRefusal.UNAVAILABLE, EscalateRefusal.REJECTED -> "claude_unavailable"
    }
}
