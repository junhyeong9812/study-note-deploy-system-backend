package xyz.junproject.backend.content.usecase

import org.springframework.stereotype.Service
import xyz.junproject.backend.shared.infra.RequestLog
import xyz.junproject.backend.sync.usecase.HistoryState

/**
 * 내용의 변경 이력 — 최근 변경은 동기화가 끝날 때 한 번 계산해 쥔다(요청마다 git 을 띄우지 않는다. 재시작 뒤 첫 요청에서 만든다).
 * 글 하나의 변경 목록은 요청 때 계산한다.
 */
@Service
class HistoryService(private val git: NoteSourcePort, private val requestLog: RequestLog) {

    data class RecentChanges(val commitSha: String, val commits: List<CommitChanges>)

    /** 동기화가 끝날 때 — 계산이 실패하면 던지지 않고 WARN 을 남기며 앞의 것을 그대로 쥔다(동기화를 실패로 만들지 않는다) */
    fun rebuild(requestId: String, commitSha: String): Unit = TODO("H1")

    /** 쥔 최근 변경 — 없으면 지금 판으로 만든다. git 의 장애는 IllegalStateException(빈 목록이 아니다) */
    fun recent(requestId: String): RecentChanges = TODO("H1")

    /** 글 하나의 변경 목록 — [REVISION_LIMIT] 개까지 */
    fun documentHistory(requestId: String, path: String): DocHistory = TODO("H1")

    fun historyState(): HistoryState = TODO("H1")

    companion object {
        /** 최근 변경에 싣는 커밋의 수 */
        const val RECENT_COMMIT_LIMIT = 30
        /** 최근 변경을 찾으려 훑는 커밋의 수(숨김 폴더만 고친 커밋은 건너뛴다) */
        const val RECENT_SCAN_LIMIT = 200
        /** 커밋 하나에 싣는 글의 수 — 넘으면 "외 n개" */
        const val DOCS_PER_COMMIT_LIMIT = 50
        /** 글 하나의 변경 목록에 싣는 수 */
        const val REVISION_LIMIT = 100
    }
}
