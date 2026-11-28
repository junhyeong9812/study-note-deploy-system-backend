package xyz.junproject.backend.content.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.content.domain.PathGuard
import xyz.junproject.backend.content.usecase.ChangedDoc
import xyz.junproject.backend.content.usecase.CommitChanges
import xyz.junproject.backend.content.usecase.DocRevision
import xyz.junproject.backend.content.usecase.HistoryService
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog
import xyz.junproject.backend.sync.usecase.HistoryState

/** 내용의 변경 이력 경로 `/api/history/…` — 최근 변경 · 글 하나의 변경 목록 */
@RestController
@RequestMapping("/api/history")
class HistoryController(
    private val history: HistoryService,
    private val requestLog: RequestLog,
) {
    @GetMapping("/recent")
    fun recent(@RequestHeader("X-Request-Id", required = false) incomingId: String?): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val recent = try {
            history.recent(requestId)
        } catch (error: IllegalStateException) {
            return unavailable(requestId, "history recent", error)
        }
        val state = history.historyState()
        requestLog.log(requestId, "history recent ${recent.commits.size} commits sha=${recent.commitSha}")
        return ResponseEntity.ok(Envelope.ok(mapOf(
            "commit_sha" to recent.commitSha,
            "complete" to isComplete(state),
            "history" to historyJson(state),
            "commits" to recent.commits.map { commit -> commitJson(commit) },
        )))
    }

    @GetMapping("/doc")
    fun document(
        @RequestParam path: String,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        if (path.length > PATH_LENGTH_LIMIT || path.any { character -> Character.isISOControl(character) } || !PathGuard.isSafeMarkdownPath(path)) {
            requestLog.log(requestId, "history doc rejected: unsafe path", "warning")
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Envelope.fail("invalid_request", detail = "path must be a repo-relative .md"))
        }
        if (PathGuard.isHidden(path)) {   // 숨김(숨김 폴더 · 실습 폴더)은 존재 은닉(404) — /api/doc 과 같다
            requestLog.log(requestId, "history doc hidden: $path", "warning")
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Envelope.fail("not_found"))
        }
        val documentHistory = try {
            history.documentHistory(requestId, path)
        } catch (_: java.io.FileNotFoundException) {
            requestLog.log(requestId, "history doc not found: $path", "warning")
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Envelope.fail("not_found"))
        } catch (error: IllegalStateException) {
            return unavailable(requestId, "history doc $path", error)
        }
        val state = history.historyState()
        requestLog.log(requestId, "history doc ok $path ${documentHistory.revisions.size} revisions")
        return ResponseEntity.ok(Envelope.ok(mapOf(
            "path" to path,
            "complete" to isComplete(state),
            "history" to historyJson(state),
            "revisions" to documentHistory.revisions.map { revision -> revisionJson(revision) },
            "more_revisions" to documentHistory.moreRevisions,
        )))
    }

    /** git 의 장애 · 시간 초과 · 동시 읽기 상한 초과 — 빈 목록이 아니라 503 */
    private fun unavailable(requestId: String, what: String, error: IllegalStateException): ResponseEntity<Map<String, Any?>> {
        requestLog.log(requestId, "$what unavailable: ${error.message?.take(300)}", "warning")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Envelope.fail("content_unavailable"))
    }

    /** 이력을 다 갖고 있다고 알려졌을 때만 참 — 얕음 · 아직 모름이면 거짓 */
    private fun isComplete(state: HistoryState): Boolean = state.shallow == false

    private fun historyJson(state: HistoryState): Map<String, Any?> = mapOf("shallow" to state.shallow, "problem" to state.problem)

    /** 커밋의 칸은 sha · 시각 · 제목 · 글 · 넘친 수뿐 */
    private fun commitJson(commit: CommitChanges): Map<String, Any?> = mapOf(
        "sha" to commit.sha, "at" to commit.at, "title" to commit.title,
        "docs" to commit.docs.map { doc -> docJson(doc) }, "more_docs" to commit.moreDocs,
    )

    private fun docJson(doc: ChangedDoc): Map<String, Any?> = buildMap {
        put("path", doc.path)
        put("kind", doc.kind.name.lowercase())
        doc.from?.let { from -> put("from", from) }
    }

    private fun revisionJson(revision: DocRevision): Map<String, Any?> =
        mapOf("sha" to revision.sha, "at" to revision.at, "title" to revision.title, "path" to revision.path)

    companion object {
        /** 받는 경로의 최대 길이(글자) */
        const val PATH_LENGTH_LIMIT = 1024
    }
}
