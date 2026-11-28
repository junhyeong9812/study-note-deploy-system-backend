package xyz.junproject.backend.content.domain

/**
 * repo 상대 .md 경로만 허용 — 트래버설·.git 차단 (content·chat 공용).
 * 마디 `.` 과 빈 마디(`//` · 처음 · 끝의 `/`)도 거절한다 — 파일 읽기 · git 이 경로를 정규화하면 `./docs/x.md` 가 숨김 폴더의 글을 가리킨다
 */
object PathGuard {
    fun isSafeMarkdownPath(path: String): Boolean =
        path.endsWith(".md") && !path.startsWith("/") && !path.startsWith("~") &&
        !path.split("/").any { it == ".." || it == "." || it.isEmpty() || it == ".git" } && path.isNotBlank()

    /** 위키에서 완전히 숨기는 최상위 폴더 (트리·색인·검색·챗봇·/doc 공통 denylist). */
    val HIDDEN_TOP_LEVEL = setOf("docs", "templates")

    /** 최상위 세그먼트가 denylist면 숨김. 루트 직속 파일(index.md 등)은 세그먼트=파일명이라 비대상. */
    fun isHidden(path: String): Boolean = path.substringBefore("/") in HIDDEN_TOP_LEVEL
}
