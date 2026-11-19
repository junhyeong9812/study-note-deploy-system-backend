package xyz.junproject.backend.shared.infra

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.ChatStreamPort
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** llm /chat 이벤트 스트림 소비 — 한 줄 = JSON 이벤트 하나(application/x-ndjson). */
@Component
class LlmChatStreamClient(
    private val objectMapper: ObjectMapper,
    private val llmUrl: String,
    private val idleLimit: Duration,      // 본문에서 이 시간 동안 한 줄도 안 오면 llm이 멈춘 것으로 본다
    private val headerTimeout: Duration = Duration.ofSeconds(30),   // 응답 헤더까지 — 대기열도 헤더는 즉시 보낸다
) : ChatStreamPort {

    // 유휴 상한 150s — llm이 Ollama를 기다리는 한도(read 120s)보다 길게: llm이 스스로 오류를 낼 시간을 준다
    @Autowired
    constructor(objectMapper: ObjectMapper) :
        this(objectMapper, System.getenv("LLM_URL") ?: "http://llm:8000", Duration.ofSeconds(150))

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val idleWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "llm-stream-idle-watchdog").apply { isDaemon = true }
    }

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
            .timeout(headerTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (error: IOException) {
            // 연결 거부·연결/헤더 타임아웃·연결 직후 끊김 — 응답을 시작도 못 했으면 서버가 꺼진 것으로 본다
            throw LlmUnreachable(error)
        } catch (error: UnresolvedAddressException) {
            throw LlmUnreachable(error)
        }
        response.body().use { input ->
            if (response.statusCode() != 200) {
                onEvent(ChatEvent.Error("upstream"))
                return
            }
            var finished = false
            var pendingIdleClose: ScheduledFuture<*>? = null
            fun restartIdleTimer() {
                pendingIdleClose?.cancel(false)
                pendingIdleClose = idleWatchdog.schedule(
                    { runCatching { input.close() } }, idleLimit.toMillis(), TimeUnit.MILLISECONDS)
            }
            restartIdleTimer()
            try {
                input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    restartIdleTimer()
                    if (finished || line.isBlank()) return@forEachLine
                    val event = parseEventLine(line) ?: return@forEachLine
                    onEvent(event)
                    if (event is ChatEvent.Done || event is ChatEvent.Error) finished = true
                }
            } catch (_: IOException) {
                // 응답 도중 끊김·유휴 상한 초과(감시가 스트림을 닫음)·호출부의 중단 신호 — 아래에서 오류 이벤트로 끝낸다
            } finally {
                pendingIdleClose?.cancel(false)
            }
            if (!finished) onEvent(ChatEvent.Error("upstream"))   // 끝 표시 없이 끊김 = 조용한 절단 금지
        }
    }
}
