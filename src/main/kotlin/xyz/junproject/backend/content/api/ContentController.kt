package xyz.junproject.backend.content.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog
import xyz.junproject.backend.indexing.domain.DocClassifier
import xyz.junproject.backend.content.usecase.NoteSourcePort

/** 콘텐츠 API — 문서 목록·원문. 렌더는 front 책임(설계: backend는 콘텐츠 JSON만). */
@RestController
@RequestMapping("/api")
class ContentController(
    private val git: NoteSourcePort,
    private val treeService: xyz.junproject.backend.content.usecase.TreeService,
    private val requestLog: RequestLog,
) {

    @GetMapping("/tree")
    fun tree(@RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val cached = treeService.get(requestId)
        return ResponseEntity.ok(Envelope.ok(mapOf(
            "commit_sha" to cached.commitSha, "tree" to cached.tree)))
    }

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

    @GetMapping("/history")
    fun history(@RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val commits = git.recentCommits(30)
        requestLog.log(requestId, "history ${commits.size} commits")
        return ResponseEntity.ok(Envelope.ok(mapOf("commits" to commits)))
    }

    @GetMapping("/doc")
    fun getDoc(@RequestParam path: String,
               @RequestParam(required = false) at: String?,
               @RequestHeader("X-Request-Id", required = false) incomingId: String?):
            ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        if (!isSafe(path)) {
            requestLog.log(requestId, "doc rejected: unsafe path $path", "warning")
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "path must be a repo-relative .md"))
        }
        if (at != null && !at.matches(Regex("^[0-9a-f]{7,64}$"))) {   // 시점 조회 — hex만 (주입 차단)
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "at must be a commit sha"))
        }
        val content = try {
            if (at != null) git.readFileAt(at, path) else git.readFile(path)
        } catch (_: java.io.FileNotFoundException) {           // 부재만 404 — I/O 장애는 전역 500 봉투로
            requestLog.log(requestId, "doc not found: $path", "warning")
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Envelope.fail("not_found"))
        } catch (_: java.nio.file.NoSuchFileException) {
            requestLog.log(requestId, "doc not found: $path", "warning")
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Envelope.fail("not_found"))
        }
        val meta = DocClassifier.classify(path)
        requestLog.log(requestId, "doc ok $path ${content.length}chars${if (at != null) " at=$at" else ""}")
        return ResponseEntity.ok(Envelope.ok(mapOf(
            "path" to meta.path, "topic" to meta.topic, "subject" to meta.subject,
            "doc_kind" to meta.docKind, "form" to meta.form, "markdown" to content,
            "at" to at,
        )))
    }

    internal fun isSafe(path: String): Boolean =
        xyz.junproject.backend.content.domain.PathGuard.isSafeMarkdownPath(path)
}
