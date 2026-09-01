package xyz.junproject.backend.content.domain

/** repo 상대 .md 경로만 허용 — 트래버설·.git 차단 (content·chat 공용) */
object PathGuard {
    fun isSafeMarkdownPath(path: String): Boolean =
        path.endsWith(".md") && !path.startsWith("/") && !path.startsWith("~") &&
        !path.split("/").any { it == ".." || it == ".git" } && path.isNotBlank()
}
