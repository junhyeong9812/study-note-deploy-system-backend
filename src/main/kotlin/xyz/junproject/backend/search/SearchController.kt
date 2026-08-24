package xyz.junproject.backend.search

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.common.Envelope
import xyz.junproject.backend.common.RequestLog

@RestController
@RequestMapping("/api")
class SearchController(private val searchService: SearchService, private val requestLog: RequestLog) {

    @GetMapping("/search")
    fun search(@RequestParam q: String,
               @RequestParam(required = false) topic: String?,
               @RequestParam(defaultValue = "10") size: Int,
               @RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = incomingId ?: requestLog.newRequestId()   // 발행 주체 = backend (규약)
        if (q.isBlank() || q.length > 300 || size !in 1..50) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "q 1~300자, size 1~50"))
        }
        return try {
            val result = searchService.search(requestId, q, topic, size)
            ResponseEntity.ok(Envelope.ok(result + mapOf("request_id" to requestId)))
        } catch (error: Exception) {
            requestLog.log(requestId, "search failed: ${error.message?.take(200)}", "error")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Envelope.fail("search_backend", detail = error.message?.take(200)))
        }
    }
}
