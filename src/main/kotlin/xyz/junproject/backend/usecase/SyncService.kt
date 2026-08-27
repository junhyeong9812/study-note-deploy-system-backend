package xyz.junproject.backend.usecase

import xyz.junproject.backend.infra.GitRepository

import org.springframework.stereotype.Service
import xyz.junproject.backend.infra.RequestLog
import xyz.junproject.backend.usecase.IndexingService
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
    private val tree: TreeService,
    private val requestLog: RequestLog,
) {
    private val stateFile = File(System.getenv("STATE_FILE") ?: "/data/study-note/last_sha")
    private val inFlightSha = AtomicReference<String?>(null)
    @Volatile private var lastResult: Map<String, Any?> = emptyMap()
    @Volatile private var startedAt: String? = null
    @Volatile private var progress: String? = null

    sealed interface Decision {
        data class Skip(val reason: String) : Decision
        data object Busy : Decision
        data class Started(val note: String) : Decision
    }

    fun requestSync(requestId: String, commitSha: String?, full: Boolean = false): Decision {
        val lastSha = readLastSha()
        if (!full && commitSha != null && commitSha == lastSha) return Decision.Skip("duplicate")
        val current = inFlightSha.get()
        if (current != null) {
            return if (commitSha != null && commitSha == current) Decision.Skip("in_progress")
            else Decision.Busy
        }
        if (!inFlightSha.compareAndSet(null, commitSha ?: "unknown")) return Decision.Busy
        thread(name = "sync-worker") { runPipeline(requestId, if (full) null else lastSha, full) }
        return Decision.Started(if (full || lastSha == null) "full_index" else "incremental")
    }

    fun status(): Map<String, Any?> = mapOf(
        "in_flight_sha" to inFlightSha.get(),
        "started_at" to startedAt,
        "progress" to progress,
        "last_processed_sha" to readLastSha(),
        "last_result" to lastResult,
    )

    private fun runPipeline(requestId: String, prevSha: String?, full: Boolean = false) {
        val startedMs = System.currentTimeMillis()
        startedAt = java.time.Instant.now().toString()
        progress = null
        try {
            val headSha = git.syncToRemoteHead()
            // 요청 SHA(멱등 키)는 유지하고, 원격 HEAD가 다르면 기록만 (B13 — Actions 경합 관찰용)
            if (inFlightSha.get() != headSha && inFlightSha.get() != "unknown") {
                requestLog.log(requestId, "sync note: requested=${inFlightSha.get()} head=$headSha")
            }
            if (!full && headSha == prevSha) {
                requestLog.log(requestId, "sync no-op: already at $headSha")
                lastResult = mapOf("outcome" to "no_change", "sha" to headSha)
                return
            }
            val (changes, effectiveFull) = try {
                if (prevSha == null) git.allMarkdown().map { 'A' to it } to true
                else git.changedMarkdown(prevSha, headSha) to false
            } catch (_: GitRepository.ShaUnresolvable) {
                // shallow 경계 밖 — 영구 스톨 대신 전체 재색인으로 강등 (B7)
                requestLog.log(requestId, "sync degrade: prev=$prevSha unresolvable -> FULL", "warning")
                git.allMarkdown().map { 'A' to it } to true
            }
            requestLog.log(requestId,
                "sync start ${if (effectiveFull) "FULL" else prevSha}..$headSha files=${changes.size}")
            val chunks = indexing.indexPaths(requestId, changes, headSha) { done, total ->
                progress = "$done/$total"
            }
            if (effectiveFull) indexing.deleteStale(requestId, headSha)   // 고아 정리 (B7)
            tree.rebuild(requestId, headSha)   // 트리는 sync 성공 시에만 변한다 (#12)
            writeLastSha(headSha)      // 전량 성공 후에만 전진 (D5-3)
            val tookMs = System.currentTimeMillis() - startedMs
            requestLog.log(requestId, "sync ok sha=$headSha files=${changes.size} chunks=$chunks ${tookMs}ms")
            lastResult = mapOf("outcome" to "ok", "sha" to headSha,
                "files" to changes.size, "chunks" to chunks, "took_ms" to tookMs)
        } catch (error: Exception) {
            requestLog.log(requestId, "sync failed: ${error.message?.take(300)}", "error")
            lastResult = mapOf("outcome" to "failed", "error" to error.message?.take(300))
        } finally {
            inFlightSha.set(null)
            startedAt = null
            progress = null
        }
    }

    private fun readLastSha(): String? = stateFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
    private fun writeLastSha(sha: String) { stateFile.parentFile.mkdirs(); stateFile.writeText(sha) }
}
