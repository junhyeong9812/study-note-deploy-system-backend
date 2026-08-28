package xyz.junproject.backend.sync.usecase

/** sync가 필요로 하는 소스 저장소 능력 — 구현은 shared/infra/GitRepository (DIP: 서비스가 인터페이스 소유) */
interface SourceControlPort {
    fun syncToRemoteHead(): String
    fun changedMarkdown(prevSha: String, headSha: String): List<Pair<Char, String>>
    fun allMarkdown(): List<String>
}

class ShaUnresolvableException(message: String) : RuntimeException(message)
