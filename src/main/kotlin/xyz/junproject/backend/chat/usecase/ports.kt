package xyz.junproject.backend.chat.usecase

/** llm /chat 스트림 — 토큰 콜백 (구현: shared/infra/LlmChatStreamClient) */
interface ChatStreamPort {
    fun stream(requestId: String, messages: List<Map<String, String>>, onToken: (String) -> Unit)
}

/** 에스컬레이션(Claude 브리지 — chat-design ⑤ⓑ). 미설정이면 available=false */
interface EscalatePort {
    val available: Boolean
    fun ask(requestId: String, prompt: String): String
}
