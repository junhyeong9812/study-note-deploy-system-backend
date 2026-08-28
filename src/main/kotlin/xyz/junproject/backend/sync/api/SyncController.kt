package xyz.junproject.backend.sync.api

import xyz.junproject.backend.sync.usecase.SyncService

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog

data class SyncIn(val request_id: String? = null, val commit_sha: String? = null, val full: Boolean = false)

@RestController
@RequestMapping("/internal")
class SyncController(private val sync: SyncService, private val requestLog: RequestLog) {
    private val secret = System.getenv("SYNC_SECRET") ?: ""

    /** 상수시간 비교 (B12) — 문자열 != 는 앞자리에서 조기 종료해 타이밍 힌트를 준다 */
    private fun secretMatches(provided: String?): Boolean =
        secret.isNotBlank() && provided != null &&
        java.security.MessageDigest.isEqual(secret.toByteArray(), provided.toByteArray())

    @PostMapping("/sync")
    fun sync(@RequestHeader("X-Sync-Secret") providedSecret: String?,
             @RequestBody(required = false) body: SyncIn?): ResponseEntity<Map<String, Any?>> {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Envelope.fail("unauthorized"))
        }
        val requestId = body?.request_id ?: requestLog.newRequestId()   // 발행 주체 = backend (규약)
        return when (val decision = sync.requestSync(requestId, body?.commit_sha, body?.full ?: false)) {
            is SyncService.Decision.Skip -> {
                requestLog.log(requestId, "sync skipped: ${decision.reason} sha=${body?.commit_sha}")
                ResponseEntity.ok(Envelope.ok(mapOf("skipped" to decision.reason)))
            }
            is SyncService.Decision.Busy -> {
                requestLog.log(requestId, "sync rejected: busy", "warning")
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Envelope.fail("sync_in_progress", retryAfter = 30))
            }
            is SyncService.Decision.Started ->
                ResponseEntity.accepted().body(Envelope.ok(
                    mapOf("started" to decision.note, "request_id" to requestId)))
        }
    }

    @GetMapping("/sync/status")
    fun status(@RequestHeader("X-Sync-Secret") providedSecret: String?): ResponseEntity<Map<String, Any?>> {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Envelope.fail("unauthorized"))
        }
        return ResponseEntity.ok(Envelope.ok(sync.status()))
    }
}
