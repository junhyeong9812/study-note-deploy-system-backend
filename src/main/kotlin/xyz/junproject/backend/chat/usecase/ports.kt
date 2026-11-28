package xyz.junproject.backend.chat.usecase

/** 채팅 스트림 이벤트 — llm 대기열 계약(queued·start·token·error·done)을 그대로 옮긴 것 */
sealed interface ChatEvent {
    val name: String
    val payload: Map<String, Any>

    data class Queued(val position: Int) : ChatEvent {
        override val name = "queued"
        override val payload get() = mapOf("position" to position)
    }
    data object Start : ChatEvent {
        override val name = "start"
        override val payload = emptyMap<String, Any>()
    }
    data class Token(val text: String) : ChatEvent {
        override val name = "token"
        override val payload get() = mapOf("text" to text)
    }
    /** code: queue_full · queue_timeout · upstream (llm 발행) / llm_unreachable · internal (backend 발행) */
    data class Error(val code: String) : ChatEvent {
        override val name = "error"
        override val payload get() = mapOf("code" to code)
    }
    data object Done : ChatEvent {
        override val name = "done"
        override val payload = emptyMap<String, Any>()
    }
    /** 로컬 모델에 연결되지 못해 주인의 질문을 Claude 가 답한다 — 그 뒤의 token 이 Claude 의 답(명세 §20.3) */
    data object Escalated : ChatEvent {
        override val name = "escalated"
        override val payload = emptyMap<String, Any>()
    }
    /** Claude 의 답이 상한에서 잘렸다 — token 뒤 · done 앞에 잘렸을 때만(명세 §21-4) */
    data object Truncated : ChatEvent {
        override val name = "truncated"
        override val payload = emptyMap<String, Any>()
    }
}

/**
 * llm 서버에 연결 자체가 안 됨(꺼짐·네트워크) — 대기·혼잡과 구별해 화면에 알린다.
 * [neverConnected]: 그 질문의 **첫 시도가 연결 단계에서** 실패했다(연결 거부 · 연결 시간 초과 · 주소를 풀지 못함) — 자동 넘김의 조건(명세 §21-1).
 * 헤더 시간 초과 · 연결 직후 끊김 · 교체 중 다시 보낸 요청의 실패는 거짓(서버가 요청을 받았을 수 있다)
 */
class LlmUnreachable(cause: Throwable, val neverConnected: Boolean = false) : RuntimeException("llm unreachable: ${cause.message}", cause)

/** llm /chat 이벤트 스트림 (구현: shared/infra/LlmChatStreamClient).
 * 연결 실패는 LlmUnreachable, 그 밖의 실패는 Error 이벤트로 끝난다. onEvent가 던지면 스트림을 닫는다. */
interface ChatStreamPort {
    fun stream(requestId: String, messages: List<Map<String, String>>, onEvent: (ChatEvent) -> Unit)
}

/** Claude 가 답하지 않은 까닭(명세 §20.1) — 로그 · 응답에는 [word] */
enum class EscalateRefusal(val word: String) {
    BUSY("busy"), LIMIT_REACHED("limit_reached"), DISABLED("disabled"), TIMEOUT("timeout"), UNAVAILABLE("unavailable"), REJECTED("rejected"),
}

/** Claude 를 부른 결과 — 예외가 아니라 값(프롬프트 · 답 · 상대의 몸이 예외의 글로 새지 않게 — 명세 §21-5 · 7) */
sealed interface EscalateResult {
    data class Answered(val text: String, val truncated: Boolean) : EscalateResult
    /** [code]: 상대가 준 오류 코드(있으면 — 로그의 `code=`) */
    /** [status]: 상대가 답한 HTTP 상태 코드 — 실행 자리의 "못 씀"(unavailable)에만(로그의 `status=` · 연결하지 못했으면 없다) */
    data class Refused(val reason: EscalateRefusal, val code: String? = null, val status: Int? = null) : EscalateResult
}

/**
 * 에스컬레이션 — Claude 실행 자리(`ClaudeRunnerClient`) 또는 옛 통로(`ClaudeBridgeClient`). 주입되는 구현은 하나(명세 §21-5).
 * [available]: 부를 곳이 설정돼 있다. [autoEscalation]: 자동 넘김을 할 수 있다(실행 자리일 때만).
 * [ask]: [deadline](절대 시각)까지 연결 · 머리글 · 몸을 다 받지 못하면 취소하고 TIMEOUT. 재시도 없음.
 * 기다리는 동안 스레드가 끊기면(interrupt) 결과로 삼키지 않고 InterruptedException 을 던진다
 */
interface EscalatePort {
    val available: Boolean
    val autoEscalation: Boolean
    fun ask(requestId: String, prompt: String, deadline: java.time.Instant): EscalateResult
}

/**
 * 대화 이력(Redis `chat:{session}:{문서 경로}`)을 세션 단위로 보고 지운다 — 내 기록 지우기(명세 12 · 18).
 * sessionId 는 부르는 쪽이 꼴(`chat_session` 의 UUID 꼴)을 확인한 값이다. Redis 오류는 DataAccessException 으로 던진다.
 */
interface ChatHistoryPort {
    /** 그 세션의 대화 이력이 하나라도 있나 */
    fun hasHistory(sessionId: String): Boolean
    /** 그 세션의 대화 이력을 모두 지운다 — 다른 세션의 것은 건드리지 않는다 */
    fun deleteHistory(sessionId: String)
}
