package xyz.junproject.backend.content.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.content.usecase.HistoryService
import xyz.junproject.backend.shared.infra.RequestLog

/** 내용의 변경 이력 경로 `/api/history/…` — 최근 변경 · 글 하나의 변경 목록 */
@RestController
@RequestMapping("/api/history")
class HistoryController(
    private val history: HistoryService,
    private val requestLog: RequestLog,
) {
    @GetMapping("/recent")
    fun recent(@RequestHeader("X-Request-Id", required = false) incomingId: String?): ResponseEntity<Map<String, Any?>> = TODO("H1")

    @GetMapping("/doc")
    fun document(
        @RequestParam path: String,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> = TODO("H1")
}
