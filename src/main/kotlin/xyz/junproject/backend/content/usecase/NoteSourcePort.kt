package xyz.junproject.backend.content.usecase

import xyz.junproject.backend.sync.usecase.HistoryState

/** 커밋 하나가 바꾼 글 하나의 구분 — 응답에는 소문자 이름(added · modified · deleted · renamed)으로 나간다 */
enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED }

/** 바뀐 글 하나 — [from] 은 [ChangeKind.RENAMED] 일 때만 옛 경로(공개되는 경로일 때만) */
data class ChangedDoc(val path: String, val kind: ChangeKind, val from: String? = null)

/** 최근 변경의 커밋 하나 — 제목만(본문 · 글쓴이 · 메일은 싣지 않는다). [moreDocs] 는 상한을 넘어 뺀 글의 수 */
data class CommitChanges(val sha: String, val at: String, val title: String, val docs: List<ChangedDoc>, val moreDocs: Int)

/** 글 하나의 변경 하나 — [path] 는 그 판에서의 경로(이름이 바뀌었으면 옛 이름) */
data class DocRevision(val sha: String, val at: String, val title: String, val path: String)

/** 글 하나의 변경 목록 — 새것부터. [moreRevisions] 는 상한을 넘어 뺀 수 */
data class DocHistory(val revisions: List<DocRevision>, val moreRevisions: Int)

/** content가 필요로 하는 노트 소스 능력 — 구현은 shared/infra/GitRepository (DIP) */
interface NoteSourcePort {
    fun allMarkdown(): List<String>
    fun currentHead(): String
    fun readFile(path: String): String
    fun readFileAt(sha: String, path: String): String   // 그 커밋 시점의 내용 (git show)

    /**
     * 최근 커밋 [scanLimit] 개를 새것부터 훑어, 공개되는 글을 하나라도 바꾼 커밋을 [commitLimit] 개까지(병합 커밋은 빼고).
     * 커밋마다 글은 [docLimit] 개까지. 빈 저장소는 빈 목록. git 의 장애 · 시간 초과는 IllegalStateException
     */
    fun recentChanges(commitLimit: Int, scanLimit: Int, docLimit: Int): List<CommitChanges>

    /**
     * 지금 판에 있는 글 [path] 의 변경 목록(이름 바뀜을 따라간다 — 공개되지 않는 경로에 있던 때의 판은 뺀다), [limit] 개까지.
     * 지금 판에 없는 글은 FileNotFoundException. git 의 장애 · 시간 초과 · 동시 읽기 상한 초과는 IllegalStateException
     */
    fun documentHistory(path: String, limit: Int): DocHistory

    /** 마지막 동기화가 알아낸 이력의 상태(git 을 띄우지 않는다) */
    fun historyState(): HistoryState
}
