package xyz.junproject.backend.chat.api

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.content.domain.PathGuard
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog
import java.util.UUID
import java.util.concurrent.Executors

data class ChatIn(val doc_path: String? = null, val question: String? = null)

/** 채팅 API — 세션 쿠키(id만, HttpOnly) 발급/검증은 여기(중앙 발급 규약). 스트림은 MVC 비동기. */
@RestController
@RequestMapping("/api/chat")
class ChatController(private val chatService: ChatService, private val requestLog: RequestLog) {

    private val streamExecutor = Executors.newFixedThreadPool(3)   // llm 세마포어(2)와 정렬 (비교 문서 §4)
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

    @PostMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun chat(@RequestBody body: ChatIn,
             request: HttpServletRequest, response: HttpServletResponse,
             @RequestHeader("X-Request-Id", required = false) incomingId: String?): Any {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val docPath = body.doc_path ?: ""
        val question = body.question?.trim() ?: ""
        if (!PathGuard.isSafeMarkdownPath(docPath) || question.isEmpty() || question.length > 2000) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "doc_path(.md)·question(1~2000자)"))
        }
        val sessionId = sessionOf(request, response)
        val emitter = ResponseBodyEmitter(180_000L)
        streamExecutor.execute {
            try {
                chatService.chat(requestId, sessionId, docPath, question) { token ->
                    emitter.send(token, MediaType.TEXT_PLAIN)
                }
                emitter.complete()
            } catch (error: Exception) {
                requestLog.log(requestId, "chat failed: ${error.message?.take(150)}", "error")
                runCatching { emitter.send("\n[오류] 응답 생성에 실패했습니다. 잠시 후 다시 시도해주세요.") }
                emitter.completeWithError(error)   // complete 누락 = 연결·스레드 누수 (비교 문서 §4)
            }
        }
        return emitter
    }

    @GetMapping("/history")
    fun history(@RequestParam doc_path: String,
                request: HttpServletRequest, response: HttpServletResponse):
            ResponseEntity<Map<String, Any?>> {
        if (!PathGuard.isSafeMarkdownPath(doc_path)) {
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
        if (!PathGuard.isSafeMarkdownPath(docPath) || question.isEmpty()) {
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
