package xyz.junproject.backend.chat.api

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.ChatEvent
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.chat.usecase.LlmUnreachable
import xyz.junproject.backend.content.domain.PathGuard
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ChatIn(val doc_path: String? = null, val question: String? = null)

/** 채팅 API — 세션 쿠키(id만, HttpOnly) 발급/검증은 여기(중앙 발급 규약). 스트림은 MVC 비동기. */
@RestController
@RequestMapping("/api/chat")
class ChatController(private val chatService: ChatService, private val requestLog: RequestLog,
                     private val objectMapper: ObjectMapper) {

    // 가상 스레드 — 대기열에 선 연결(최대 2+8)을 플랫폼 스레드 없이 붙잡는다. 동시 수 상한은 llm 대기열이 소유
    private val streamExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val keepAliveScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "chat-sse-keep-alive").apply { isDaemon = true }
    }

    private companion object {
        const val KEEP_ALIVE_SECONDS = 15L
        // 한글이 변환기 기본 문자셋(ISO-8859-1)에 깨지지 않게 문자셋을 명시한다
        val UTF8_TEXT = MediaType("text", "plain", Charsets.UTF_8)
    }
    private val sessionPattern = Regex("^[0-9a-f-]{36}$")

    private fun sessionOf(request: HttpServletRequest, response: HttpServletResponse): String {
        val existing = request.cookies?.firstOrNull { it.name == "chat_session" }?.value
        if (existing != null && sessionPattern.matches(existing)) return existing
        val issued = UUID.randomUUID().toString()
        response.addCookie(Cookie("chat_session", issued).apply {
            isHttpOnly = true; path = "/"; maxAge = 60 * 60 * 24 * 30
        })
        return issued
    }

    /** 클라이언트가 떠나 전송이 실패함 — 오류가 아니라 스트림을 접는 신호 */
    private class ClientGone(cause: Throwable) : RuntimeException(cause)

    @PostMapping
    fun chat(@RequestBody body: ChatIn,
             request: HttpServletRequest, response: HttpServletResponse,
             @RequestHeader("X-Request-Id", required = false) incomingId: String?,
             @RequestHeader("Accept", required = false) accept: String?): Any {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val docPath = body.doc_path ?: ""
        val question = body.question?.trim() ?: ""
        if (!PathGuard.isSafeMarkdownPath(docPath) || PathGuard.isHidden(docPath) ||
            question.isEmpty() || question.length > 2000) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Envelope.fail("invalid_request", detail = "doc_path(.md)·question(1~2000자)"))
        }
        val sessionId = sessionOf(request, response)
        return if (accept?.contains(MediaType.TEXT_EVENT_STREAM_VALUE) == true)
            chatEvents(requestId, sessionId, docPath, question, response)
        else chatPlainText(requestId, sessionId, docPath, question)
    }

    /** SSE — 대기(queued)·시작·토큰·오류·완료를 이벤트로. 오류도 이벤트다(본문에 글자를 섞지 않는다). */
    private fun chatEvents(requestId: String, sessionId: String, docPath: String, question: String,
                           response: HttpServletResponse): SseEmitter {
        response.setHeader("X-Accel-Buffering", "no")     // 엣지 nginx가 이벤트를 모아두지 않게
        response.setHeader("Cache-Control", "no-store")
        response.characterEncoding = "UTF-8"              // 한글 토큰이 응답 기본 문자셋에 깨지지 않게
        val emitter = SseEmitter(180_000L)
        fun send(event: ChatEvent) {
            try {
                emitter.send(SseEmitter.event().name(event.name)
                    .data(objectMapper.writeValueAsString(event.payload), UTF8_TEXT))
            } catch (error: Exception) {
                throw ClientGone(error)
            }
        }
        // 브라우저가 떠난 걸 알게 되면 스트림 작업을 깨운다 — llm 읽기가 끊기며 대기 자리·생성 슬롯이 반납된다.
        // (토큰이 안 오는 구간에는 전송 실패로 알 기회가 keep-alive뿐이다)
        val streamWorker = AtomicReference<Thread>()
        val clientGone = AtomicBoolean(false)
        fun stopStreamWorker() {
            clientGone.set(true)                 // 작업이 아직 시작 전이어도 시작하자마자 알 수 있게 남긴다
            streamWorker.get()?.interrupt()
        }
        // 이벤트 사이가 길어져도(대기·첫 토큰 전) 엣지 프록시의 무응답 제한(60s)에 끊기지 않게 주석 줄을 흘린다
        val keepAlive = keepAliveScheduler.scheduleAtFixedRate({
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"))
            } catch (_: Exception) {
                stopStreamWorker()
                throw CancellationException("client gone")   // 예외로 끝내면 이 주기 작업은 다시 돌지 않는다
            }
        }, KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
        emitter.onCompletion { keepAlive.cancel(false) }
        emitter.onTimeout { keepAlive.cancel(false); stopStreamWorker() }
        emitter.onError { keepAlive.cancel(false); stopStreamWorker() }
        streamExecutor.execute {
            streamWorker.set(Thread.currentThread())
            if (clientGone.get()) Thread.currentThread().interrupt()   // 등록 전에 온 이탈 신호를 놓치지 않는다
            try {
                chatService.chat(requestId, sessionId, docPath, question, ::send)
                emitter.complete()
            } catch (gone: ClientGone) {
                // 던져진 예외가 llm 스트림을 닫는다 → llm이 대기 자리·생성 슬롯을 반납
                requestLog.log(requestId, "chat client gone")
                emitter.completeWithError(gone)
            } catch (error: Exception) {
                val code = if (error is LlmUnreachable) "llm_unreachable" else "internal"
                requestLog.log(requestId, "chat failed($code): ${error.message?.take(150)}", "error")
                runCatching { send(ChatEvent.Error(code)) }
                emitter.complete()                // complete 누락 = 연결·스레드 누수 (비교 문서 §4)
            } finally {
                streamWorker.set(null)            // 끝난 뒤 늦게 온 신호가 다른 작업을 깨우지 않게
                Thread.interrupted()
            }
        }
        return emitter
    }

    /** 구 계약(text/plain 청크) — SSE를 요청하지 않는 구 front용. 토큰만 흘리고 오류는 기존 문구. */
    private fun chatPlainText(requestId: String, sessionId: String, docPath: String,
                              question: String): ResponseBodyEmitter {
        val emitter = ResponseBodyEmitter(180_000L)
        val failureText = "\n[오류] 응답 생성에 실패했습니다. 잠시 후 다시 시도해주세요."
        streamExecutor.execute {
            try {
                chatService.chat(requestId, sessionId, docPath, question) { event ->
                    when (event) {
                        is ChatEvent.Token -> emitter.send(event.text, UTF8_TEXT)
                        is ChatEvent.Error -> emitter.send(failureText, UTF8_TEXT)
                        else -> Unit
                    }
                }
                emitter.complete()
            } catch (error: Exception) {
                requestLog.log(requestId, "chat failed: ${error.message?.take(150)}", "error")
                runCatching { emitter.send(failureText, UTF8_TEXT) }
                emitter.completeWithError(error)
            }
        }
        return emitter
    }

    @GetMapping("/history")
    fun history(@RequestParam doc_path: String,
                request: HttpServletRequest, response: HttpServletResponse):
            ResponseEntity<Map<String, Any?>> {
        if (!PathGuard.isSafeMarkdownPath(doc_path) || PathGuard.isHidden(doc_path)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request"))
        }
        val sessionId = sessionOf(request, response)
        val items = chatService.history(sessionId, doc_path)
            .map { mapOf("role" to it.role, "content" to it.content) }
        return ResponseEntity.ok(Envelope.ok(mapOf("messages" to items)))
    }

    @PostMapping("/escalate")
    fun escalate(@RequestBody body: ChatIn,
                 request: HttpServletRequest, response: HttpServletResponse,
                 @RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val docPath = body.doc_path ?: ""
        val question = body.question?.trim() ?: ""
        if (!PathGuard.isSafeMarkdownPath(docPath) || PathGuard.isHidden(docPath) || question.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request"))
        }
        val sessionId = sessionOf(request, response)
        return try {
            val answer = chatService.escalateAnswer(requestId, sessionId, docPath, question)
            ResponseEntity.ok(Envelope.ok(mapOf("answer" to answer)))
        } catch (error: IllegalStateException) {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Envelope.fail("escalate_unavailable", detail = "브리지 미연결(PC 오프라인?)"))
        } catch (error: Exception) {
            requestLog.log(requestId, "escalate failed: ${error.message?.take(150)}", "error")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Envelope.fail("escalate_failed"))
        }
    }
}
