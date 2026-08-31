package xyz.junproject.backend.search.api

import xyz.junproject.backend.search.usecase.SearchService
import xyz.junproject.backend.search.usecase.SuggestService

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog

@RestController
@RequestMapping("/api")
class SearchController(
    private val searchService: SearchService,
    private val suggestService: SuggestService,
    private val requestLog: RequestLog,
) {

    @GetMapping("/suggest")
    fun suggest(@RequestParam q: String,
                @RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        if (q.isBlank() || q.length > 100) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "q 1~100자"))
        }
        return try {
            val items = suggestService.suggest(requestId, q).map {
                mapOf("path" to it.path, "title" to it.title,
                      "doc_kind" to it.docKind, "snippet" to it.snippet)
            }
            ResponseEntity.ok(Envelope.ok(mapOf("items" to items)))
        } catch (error: Exception) {
            requestLog.log(requestId, "suggest failed: ${error.message?.take(150)}", "error")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Envelope.fail("search_backend"))
        }
    }

    @GetMapping("/search")
    fun search(@RequestParam q: String,
               @RequestParam(required = false) topic: String?,
               @RequestParam(name = "doc_kind", required = false) docKinds: List<String>?,
               @RequestParam(defaultValue = "10") size: Int,
               @RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)   // 발행 주체 = backend (규약)
        if (q.isBlank() || q.length > 300 || size !in 1..50) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "q 1~300자, size 1~50"))
        }
        return try {
            val result = searchService.search(requestId, q, topic, docKinds, size)
            ResponseEntity.ok(Envelope.ok(result + mapOf("request_id" to requestId)))
        } catch (error: Exception) {
            requestLog.log(requestId, "search failed: ${error.message?.take(200)}", "error")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Envelope.fail("search_backend", detail = error.message?.take(200)))
        }
    }
}
