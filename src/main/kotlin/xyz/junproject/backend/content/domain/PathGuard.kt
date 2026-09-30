package xyz.junproject.backend.content.domain

/** repo 상대 .md 경로만 허용 — 트래버설·.git 차단 (content·chat 공용) */
object PathGuard {
    fun isSafeMarkdownPath(path: String): Boolean =
        path.endsWith(".md") && !path.startsWith("/") && !path.startsWith("~") &&
        !path.split("/").any { it == ".." || it == ".git" } && path.isNotBlank()

    /** 위키에서 완전히 숨기는 최상위 폴더 (트리·색인·검색·챗봇·/doc 공통 denylist). */
    val HIDDEN_TOP_LEVEL = setOf("docs", "templates")

    /** 최상위 세그먼트가 denylist면 숨김. 루트 직속 파일(index.md 등)은 세그먼트=파일명이라 비대상. */
    fun isHidden(path: String): Boolean = path.substringBefore("/") in HIDDEN_TOP_LEVEL
}
