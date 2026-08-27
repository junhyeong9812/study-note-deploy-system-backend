package xyz.junproject.backend.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.api.Envelope
import xyz.junproject.backend.infra.RequestLog
import xyz.junproject.backend.domain.DocClassifier
import xyz.junproject.backend.infra.GitRepository

/** 콘텐츠 API — 문서 목록·원문. 렌더는 front 책임(설계: backend는 콘텐츠 JSON만). */
@RestController
@RequestMapping("/api")
class ContentController(private val git: GitRepository, private val requestLog: RequestLog) {

    @GetMapping("/docs")
    fun listDocs(@RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val docs = git.allMarkdown().map { path ->
            val meta = DocClassifier.classify(path)
            mapOf(
                "path" to meta.path, "topic" to meta.topic, "topic_path" to meta.topicPath,
                "subject" to meta.subject, "depth" to meta.depth,
                "doc_kind" to meta.docKind, "form" to meta.form,
            )
        }
        requestLog.log(requestId, "docs list ${docs.size}")
        return ResponseEntity.ok(Envelope.ok(mapOf("count" to docs.size, "docs" to docs)))
    }

    @GetMapping("/doc")
    fun getDoc(@RequestParam path: String,
               @RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        if (!isSafe(path)) {
            requestLog.log(requestId, "doc rejected: unsafe path $path", "warning")
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "path must be a repo-relative .md"))
        }
        val content = try {
            git.readFile(path)
        } catch (_: java.io.FileNotFoundException) {           // 부재만 404 — I/O 장애는 전역 500 봉투로
            requestLog.log(requestId, "doc not found: $path", "warning")
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Envelope.fail("not_found"))
        } catch (_: java.nio.file.NoSuchFileException) {
            requestLog.log(requestId, "doc not found: $path", "warning")
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Envelope.fail("not_found"))
        }
        val meta = DocClassifier.classify(path)
        requestLog.log(requestId, "doc ok $path ${content.length}chars")
        return ResponseEntity.ok(Envelope.ok(mapOf(
            "path" to meta.path, "topic" to meta.topic, "subject" to meta.subject,
            "doc_kind" to meta.docKind, "form" to meta.form, "markdown" to content,
        )))
    }

    /** 경로 트래버설 차단 — repo 상대 .md 경로만 허용 */
    internal fun isSafe(path: String): Boolean =
        path.endsWith(".md") && !path.startsWith("/") && !path.startsWith("~") &&
        !path.split("/").any { it == ".." || it == ".git" } && path.isNotBlank()
}
