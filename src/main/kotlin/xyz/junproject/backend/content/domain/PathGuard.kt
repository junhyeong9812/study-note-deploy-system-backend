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

    /**
     * 숨김 — 최상위 세그먼트가 denylist 이거나 실습 경로([isPracticePath])다. 루트 직속 파일(index.md 등)은 세그먼트=파일명이라 비대상.
     * 숨김을 거르는 모든 곳(트리 · 글 목록 · 문서 · 옛 판 · 이력 · 변경 목록 · 색인 · 채팅)이 이 한 판정을 쓴다.
     */
    fun isHidden(path: String): Boolean = path.substringBefore("/") in HIDDEN_TOP_LEVEL || isPracticePath(path)

    /** 실습 폴더의 이름 — 주제 폴더 안의 이 폴더 아래는 글 읽기 길에서 숨긴다 */
    const val PRACTICE_FOLDER = "practice"

    /**
     * 실습 경로 — 폴더 조각 가운데 둘째 이후의 것이 정확히 `practice` 인 경로(주제 폴더 안의 `practice/` 아래 · 명세 ⑭ §10 · §11 P3a).
     * 최상위 `practice/` 는 기존 글의 자리라 아니다. 파일 이름 `practice.md` 도 아니다
     */
    fun isPracticePath(path: String): Boolean = path.split("/").dropLast(1).drop(1).any { folder -> folder == PRACTICE_FOLDER }
}
