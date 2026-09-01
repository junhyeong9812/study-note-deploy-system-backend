package xyz.junproject.backend.shared.infra

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatStreamPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** llm /chat 스트림 소비 — 청크(text/plain)를 그대로 콜백. (java HttpClient — 스트리밍 바디) */
@Component
class LlmChatStreamClient(private val objectMapper: ObjectMapper) : ChatStreamPort {
    private val llmUrl = System.getenv("LLM_URL") ?: "http://llm:8000"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override fun stream(requestId: String, messages: List<Map<String, String>>, onToken: (String) -> Unit) {
        val body = objectMapper.writeValueAsString(
            mapOf("request_id" to requestId, "messages" to messages))
        val request = HttpRequest.newBuilder(URI.create("$llmUrl/chat"))
            .timeout(Duration.ofSeconds(150))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "llm chat status ${response.statusCode()}" }
        response.body().use { input ->
            val buffer = ByteArray(512)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                onToken(String(buffer, 0, read, Charsets.UTF_8))
            }
        }
    }
}
