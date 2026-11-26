package xyz.junproject.backend.owner.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.owner.usecase.OwnerJudge
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog

/** `GET /api/owner` → `{owner: true|false}` — 캐시 금지 · `Vary: Cookie`. front 가 단추를 보일지 정한다(이것만 믿고 여는 길은 없다) */
@RestController
@RequestMapping("/api/owner")
class OwnerController(private val ownerJudge: OwnerJudge, private val requestLog: RequestLog) {
    @GetMapping
    fun owner(request: HttpServletRequest, @RequestHeader("X-Request-Id", required = false) incomingId: String?):
        ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val verdict = ownerJudge.judge(requestId, request)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
            .body(Envelope.ok(mapOf("owner" to verdict.owner)))
    }
}
