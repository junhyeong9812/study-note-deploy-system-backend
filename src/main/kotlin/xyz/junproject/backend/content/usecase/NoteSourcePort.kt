package xyz.junproject.backend.content.usecase

/** content가 필요로 하는 노트 소스 능력 — 구현은 shared/infra/GitRepository (DIP) */
data class CommitInfo(val sha: String, val message: String, val at: String)

interface NoteSourcePort {
    fun allMarkdown(): List<String>
    fun currentHead(): String
    fun readFile(path: String): String
    fun recentCommits(limit: Int): List<CommitInfo>
    fun readFileAt(sha: String, path: String): String   // 그 커밋 시점의 내용 (git show)
}
