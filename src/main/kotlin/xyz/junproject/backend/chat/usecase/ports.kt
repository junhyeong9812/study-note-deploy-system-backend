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
}

/** llm 서버에 연결 자체가 안 됨(꺼짐·네트워크) — 대기·혼잡과 구별해 화면에 알린다 */
class LlmUnreachable(cause: Throwable) : RuntimeException("llm unreachable: ${cause.message}", cause)

/** llm /chat 이벤트 스트림 (구현: shared/infra/LlmChatStreamClient).
 * 연결 실패는 LlmUnreachable, 그 밖의 실패는 Error 이벤트로 끝난다. onEvent가 던지면 스트림을 닫는다. */
interface ChatStreamPort {
    fun stream(requestId: String, messages: List<Map<String, String>>, onEvent: (ChatEvent) -> Unit)
}

/** 에스컬레이션(Claude 브리지 — chat-design ⑤ⓑ). 미설정이면 available=false */
interface EscalatePort {
    val available: Boolean
    fun ask(requestId: String, prompt: String): String
}
