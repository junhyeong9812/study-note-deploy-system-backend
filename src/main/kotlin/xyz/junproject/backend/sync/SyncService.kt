package xyz.junproject.backend.sync

import org.springframework.stereotype.Service
import xyz.junproject.backend.common.RequestLog
import xyz.junproject.backend.indexing.IndexingService
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * sync 오케스트레이션 — 멱등·single-flight (spec ①-⑵).
 * - commit_sha == 마지막 처리 SHA → skip(duplicate)
 * - 처리 중 동일 SHA → skip(in_progress) / 다른 SHA → busy(409)
 * - 성공 시에만 last_sha 전진 → 실패한 SHA의 재요청은 중복이 아니라 재처리 (멱등 재시도)
 */
@Service
class SyncService(
    private val git: GitRepository,
    private val indexing: IndexingService,
    private val requestLog: RequestLog,
) {
    private val stateFile = File(System.getenv("STATE_FILE") ?: "/data/study-note/last_sha")
    private val inFlightSha = AtomicReference<String?>(null)
    @Volatile private var lastResult: Map<String, Any?> = emptyMap()

    sealed interface Decision {
        data class Skip(val reason: String) : Decision
        data object Busy : Decision
        data class Started(val note: String) : Decision
    }

    fun requestSync(requestId: String, commitSha: String?): Decision {
        val lastSha = readLastSha()
        if (commitSha != null && commitSha == lastSha) return Decision.Skip("duplicate")
        val current = inFlightSha.get()
        if (current != null) {
            return if (commitSha != null && commitSha == current) Decision.Skip("in_progress")
            else Decision.Busy
        }
        if (!inFlightSha.compareAndSet(null, commitSha ?: "unknown")) return Decision.Busy
        thread(name = "sync-worker") { runPipeline(requestId, lastSha) }
        return Decision.Started(if (lastSha == null) "full_index" else "incremental")
    }

    fun status(): Map<String, Any?> = mapOf(
        "in_flight_sha" to inFlightSha.get(),
        "last_processed_sha" to readLastSha(),
        "last_result" to lastResult,
    )

    private fun runPipeline(requestId: String, prevSha: String?) {
        val startedAt = System.currentTimeMillis()
        try {
            val headSha = git.syncToRemoteHead()
            inFlightSha.set(headSha)   // 실제 처리 대상 SHA로 갱신 (중복 판정 정확화)
            if (headSha == prevSha) {
                requestLog.log(requestId, "sync no-op: already at $headSha")
                lastResult = mapOf("outcome" to "no_change", "sha" to headSha)
                return
            }
            val changes = if (prevSha == null) git.allMarkdown().map { 'A' to it }
                          else git.changedMarkdown(prevSha, headSha)
            requestLog.log(requestId, "sync start ${prevSha ?: "FULL"}..$headSha files=${changes.size}")
            val chunks = indexing.indexPaths(requestId, changes, headSha)
            writeLastSha(headSha)      // 전량 성공 후에만 전진 (D5-3)
            val tookMs = System.currentTimeMillis() - startedAt
            requestLog.log(requestId, "sync ok sha=$headSha files=${changes.size} chunks=$chunks ${tookMs}ms")
            lastResult = mapOf("outcome" to "ok", "sha" to headSha,
                "files" to changes.size, "chunks" to chunks, "took_ms" to tookMs)
        } catch (error: Exception) {
            requestLog.log(requestId, "sync failed: ${error.message?.take(300)}", "error")
            lastResult = mapOf("outcome" to "failed", "error" to error.message?.take(300))
        } finally {
            inFlightSha.set(null)
        }
    }

    private fun readLastSha(): String? = stateFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
    private fun writeLastSha(sha: String) { stateFile.parentFile.mkdirs(); stateFile.writeText(sha) }
}
