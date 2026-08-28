package xyz.junproject.backend.content.usecase

/** content가 필요로 하는 노트 소스 능력 — 구현은 shared/infra/GitRepository (DIP) */
interface NoteSourcePort {
    fun allMarkdown(): List<String>
    fun currentHead(): String
    fun readFile(path: String): String
}
