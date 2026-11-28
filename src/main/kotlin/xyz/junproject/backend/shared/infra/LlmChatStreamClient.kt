package xyz.junproject.backend.shared.infra

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.ChatStreamPort
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
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
    private val maxHandoffs: Int = 2,     // llm 교체 중 "다시 보내라"를 따르는 횟수 상한(한 질문당)
    private val resendPause: Duration = Duration.ofMillis(200),   // 다시 보낸 요청이 연결 단계에서 끊겼을 때 한 번 더 보내기 전 쉼
) : ChatStreamPort {
    private val log = org.slf4j.LoggerFactory.getLogger(LlmChatStreamClient::class.java)

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

    /** llm 이 교체 중이라 "다시 보내라"(retry)고 한 줄인가 — 화면에는 내보내지 않는 내부 신호 */
    private fun isRetryLine(line: String): Boolean =
        try { objectMapper.readTree(line).path("type").asString("") == "retry" } catch (_: Exception) { false }

    /**
     * llm 은 무중단 배포로 교체될 때 아직 시작하지 않은 대기자에게 retry 를 보내고 연결을 닫는다.
     * 그때는 같은 질문을 다시 보낸다 — 새 버전이 받아 줄을 다시 세우고, 화면은 "대기 중"이 이어진다.
     * 한 질문당 다시 보내기는 [maxHandoffs]번까지. 넘으면 upstream 오류로 끝낸다(무한 반복 금지).
     */
    override fun stream(requestId: String, messages: List<Map<String, String>>, onEvent: (ChatEvent) -> Unit) {
        repeat(maxHandoffs + 1) { attempt ->
            if (!streamOnce(requestId, messages, onEvent, resend = attempt > 0)) return
            if (attempt == maxHandoffs) {
                log.warn("{}:backend:chat handoff limit reached ({} resends)", requestId, maxHandoffs)
                onEvent(ChatEvent.Error("upstream"))
            } else {
                log.info("{}:backend:chat handed off by llm — resending ({}/{})", requestId, attempt + 1, maxHandoffs)
            }
        }
    }

    /** 연결 단계의 실패인가 — 연결 거부 · 연결 시간 초과 · 주소를 풀지 못함. 헤더 시간 초과 · 연결 직후 끊김은 아니다 */
    private fun failedToConnect(error: IOException): Boolean =
        error is ConnectException || error is HttpConnectTimeoutException || error.cause is UnresolvedAddressException

    /** 한 번의 요청. llm 이 retry 로 넘겼으면 true(다시 보내야 함), 그 밖에는 false(끝났거나 오류 이벤트를 냈음). */
    private fun streamOnce(requestId: String, messages: List<Map<String, String>>,
                           onEvent: (ChatEvent) -> Unit, resend: Boolean): Boolean {
        val body = objectMapper.writeValueAsString(
            mapOf("request_id" to requestId, "messages" to messages))
        val request = HttpRequest.newBuilder(URI.create("$llmUrl/chat"))
            .timeout(headerTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            // 추적 ID 를 머리글로도(다시 보내는 요청도 여기서 새로 만든다) — 꼴이 맞을 때만. 본문의 request_id 는 호환으로 그대로
            .also { builder -> if (RequestLog.isRequestId(requestId)) builder.header(RequestLog.REQUEST_ID_HEADER, requestId) }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (error: IOException) {
                // 다시 보내는 요청은 방금 응답을 마친 연결을 재사용하다가 끊길 수 있다 — 교체 중인 쪽이
                // 응답 직후 연결을 닫기 때문이다. POST 는 자동 재전송되지 않으므로 여기서 한 번 더 보낸다.
                if (!resend) throw error
                Thread.sleep(resendPause.toMillis())
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (error: IOException) {
            // 연결 거부·연결/헤더 타임아웃·연결 직후 끊김 — 응답을 시작도 못 했으면 서버가 꺼진 것으로 본다.
            // 그 가운데 첫 시도가 연결 단계에서 실패한 것만 "연결되지 못함"(서버가 요청을 받지 못했다 — 자동 넘김의 조건)
            throw LlmUnreachable(error, neverConnected = !resend && failedToConnect(error))
        } catch (error: UnresolvedAddressException) {
            throw LlmUnreachable(error, neverConnected = !resend)
        }
        response.body().use { input ->
            if (response.statusCode() != 200) {
                onEvent(ChatEvent.Error("upstream"))
                return false
            }
            var finished = false
            var started = false
            var handedOff = false
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
                    if (isRetryLine(line)) {
                        // 생성이 시작된 뒤의 retry 는 계약 밖이다 — 답이 중간에 끊긴 것으로 처리(다시 보내면 답이 두 번 나간다)
                        if (started) onEvent(ChatEvent.Error("upstream")) else handedOff = true
                        finished = true
                        return@forEachLine
                    }
                    val event = parseEventLine(line) ?: return@forEachLine
                    if (event is ChatEvent.Start) started = true
                    onEvent(event)
                    if (event is ChatEvent.Done || event is ChatEvent.Error) finished = true
                }
            } catch (_: IOException) {
                // 응답 도중 끊김·유휴 상한 초과(감시가 스트림을 닫음)·호출부의 중단 신호 — 아래에서 오류 이벤트로 끝낸다
            } finally {
                pendingIdleClose?.cancel(false)
            }
            if (!finished) onEvent(ChatEvent.Error("upstream"))   // 끝 표시 없이 끊김 = 조용한 절단 금지
            return handedOff
        }
    }
}
