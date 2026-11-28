package xyz.junproject.backend.content.usecase

import org.springframework.stereotype.Service
import xyz.junproject.backend.shared.infra.RequestLog
import xyz.junproject.backend.sync.usecase.HistoryState

/**
 * 내용의 변경 이력 — 최근 변경은 동기화가 끝날 때 한 번 계산해 쥔다(요청마다 git 을 띄우지 않는다. 재시작 뒤 첫 요청에서 만든다).
 * 글 하나의 변경 목록은 요청 때 계산한다.
 */
@Service
class HistoryService(
    private val git: NoteSourcePort,
    private val requestLog: RequestLog,
    /** 실패를 쥐는 시간을 재는 시계 — 테스트가 바꾼다 */
    private val clock: java.time.Clock,
    /** 쥔 것이 없을 때의 계산이 실패하면 그 실패를 이만큼 쥔다(그동안의 요청은 git 을 띄우지 않고 장애) */
    private val failureHold: java.time.Duration,
) {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(git: NoteSourcePort, requestLog: RequestLog) : this(git, requestLog, java.time.Clock.systemUTC(), RECENT_FAILURE_HOLD)

    data class RecentChanges(val commitSha: String, val commits: List<CommitChanges>)

    /** 쥔 최근 변경과 그것을 바꿔 끼운 차례(동기화의 rebuild 마다 하나씩 는다) — 둘을 함께 바꾸려고 한 값으로 둔다 */
    private data class Held(val recent: RecentChanges?, val generation: Long)

    /** 쥔 것이 없을 때의 계산이 실패한 기록 — [until] 까지는 git 을 다시 띄우지 않는다 */
    private data class HeldFailure(val until: java.time.Instant, val message: String)

    @Volatile private var held = Held(recent = null, generation = 0)
    @Volatile private var heldFailure: HeldFailure? = null

    /** 쥔 것이 없을 때의 계산 — 한 번에 하나, 잠금을 기다리지 않는다(tryLock). git 은 이 잠금 밖의 어떤 synchronized 안에서도 부르지 않는다 */
    private val firstComputation = java.util.concurrent.locks.ReentrantLock()

    /** 동기화가 끝날 때 — 계산이 실패하면 던지지 않고 WARN 을 남기며 앞의 것을 그대로 쥔다(동기화를 실패로 만들지 않는다). 첫 계산을 기다리지 않는다 */
    fun rebuild(requestId: String, commitSha: String) {
        val commits = try {
            git.recentChanges(RECENT_COMMIT_LIMIT, RECENT_SCAN_LIMIT, DOCS_PER_COMMIT_LIMIT)
        } catch (error: Exception) {
            requestLog.log(requestId, "history rebuild failed sha=$commitSha (keeps sha=${held.recent?.commitSha}): ${error.message?.take(300)}", "warning")
            return
        }
        synchronized(this) {
            held = Held(RecentChanges(commitSha, commits), held.generation + 1)
            heldFailure = null
        }
        requestLog.log(requestId, "history rebuilt sha=$commitSha commits=${commits.size}")
    }

    /**
     * 쥔 최근 변경 — 없으면 지금 판으로 만든다. git 의 장애는 IllegalStateException(빈 목록이 아니다).
     * 첫 계산이 도는 동안 온 요청 · 실패를 쥐는 동안([failureHold])의 요청은 git 을 띄우지 않고 곧바로 IllegalStateException
     */
    fun recent(requestId: String): RecentChanges {
        held.recent?.let { return it }
        heldFailure?.let { failure ->
            if (clock.instant().isBefore(failure.until)) error("최근 변경의 첫 계산이 방금 실패했다(다시 계산하기 전): ${failure.message}")
        }
        if (!firstComputation.tryLock()) {
            held.recent?.let { return it }
            error("최근 변경의 첫 계산이 도는 중이다")
        }
        try {
            held.recent?.let { return it }
            val startGeneration = held.generation
            val computed = try {
                val headSha = git.currentHead()
                RecentChanges(headSha, git.recentChanges(RECENT_COMMIT_LIMIT, RECENT_SCAN_LIMIT, DOCS_PER_COMMIT_LIMIT))
            } catch (error: Exception) {
                val message = error.message?.take(300) ?: error.javaClass.simpleName
                synchronized(this) { heldFailure = HeldFailure(clock.instant().plus(failureHold), message) }
                requestLog.log(requestId, "history first build failed (held ${failureHold.toMillis()}ms): $message", "warning")
                throw if (error is IllegalStateException) error else IllegalStateException(message, error)
            }
            // 계산이 도는 동안 동기화가 새 판을 끼웠으면 그것을 덮지 않는다 — 이 요청에는 제 결과를 돌려준다
            synchronized(this) {
                if (held.generation == startGeneration && held.recent == null) {
                    held = Held(computed, startGeneration)
                    heldFailure = null
                }
            }
            requestLog.log(requestId, "history built lazily sha=${computed.commitSha} commits=${computed.commits.size}")
            return computed
        } finally {
            firstComputation.unlock()
        }
    }

    /** 글 하나의 변경 목록 — [REVISION_LIMIT] 개까지. 없음(FileNotFoundException) · 장애(IllegalStateException)는 그대로 올린다 */
    fun documentHistory(requestId: String, path: String): DocHistory = git.documentHistory(path, REVISION_LIMIT)

    fun historyState(): HistoryState = git.historyState()

    companion object {
        /** 최근 변경에 싣는 커밋의 수 */
        const val RECENT_COMMIT_LIMIT = 30
        /** 최근 변경을 찾으려 훑는 커밋의 수(숨김 폴더 · 실습 폴더만 고친 커밋은 건너뛴다) */
        const val RECENT_SCAN_LIMIT = 200
        /** 커밋 하나에 싣는 글의 수 — 넘으면 "외 n개" */
        const val DOCS_PER_COMMIT_LIMIT = 50
        /** 글 하나의 변경 목록에 싣는 수 */
        const val REVISION_LIMIT = 100
        /** 첫 요청의 계산이 실패했을 때 그 실패를 쥐는 기본 시간 */
        val RECENT_FAILURE_HOLD: java.time.Duration = java.time.Duration.ofSeconds(5)
    }
}
