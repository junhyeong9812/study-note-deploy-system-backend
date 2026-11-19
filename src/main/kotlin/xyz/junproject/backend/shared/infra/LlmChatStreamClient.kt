package xyz.junproject.backend.shared.infra

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.ChatStreamPort
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.time.Duration

/** llm /chat 이벤트 스트림 소비 — 한 줄 = JSON 이벤트 하나(application/x-ndjson). */
@Component
class LlmChatStreamClient(private val objectMapper: ObjectMapper) : ChatStreamPort {
    private val llmUrl = System.getenv("LLM_URL") ?: "http://llm:8000"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    /** 알 수 없는 줄은 null — 계약 밖 이벤트로 스트림을 깨지 않는다 */
    fun parseEventLine(line: String): ChatEvent? {
        val node = try { objectMapper.readTree(line) } catch (_: Exception) { return null }
        return when (node.path("type").asString("")) {
            "queued" -> ChatEvent.Queued(node.path("position").asInt(0))
            "start" -> ChatEvent.Start
            "token" -> ChatEvent.Token(node.path("text").asString(""))
            "error" -> ChatEvent.Error(node.path("code").asString("upstream"))
            "done" -> ChatEvent.Done
            else -> null
        }
    }

    override fun stream(requestId: String, messages: List<Map<String, String>>, onEvent: (ChatEvent) -> Unit) {
        val body = objectMapper.writeValueAsString(
            mapOf("request_id" to requestId, "messages" to messages))
        val request = HttpRequest.newBuilder(URI.create("$llmUrl/chat"))
            .timeout(Duration.ofSeconds(150))          // 응답 헤더까지 — 대기열은 헤더를 즉시 보내므로 대기 시간과 무관
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (error: Exception) {
            when (error) {
                is ConnectException, is HttpConnectTimeoutException,
                is UnknownHostException, is UnresolvedAddressException -> throw LlmUnreachable(error)
                else -> throw error
            }
        }
        response.body().use { input ->
            if (response.statusCode() != 200) {
                onEvent(ChatEvent.Error("upstream"))
                return
            }
            var finished = false
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (finished || line.isBlank()) return@forEachLine
                val event = parseEventLine(line) ?: return@forEachLine
                onEvent(event)
                if (event is ChatEvent.Done || event is ChatEvent.Error) finished = true
            }
            if (!finished) onEvent(ChatEvent.Error("upstream"))   // 끝 표시 없이 끊김 = 조용한 절단 금지
        }
    }
}
