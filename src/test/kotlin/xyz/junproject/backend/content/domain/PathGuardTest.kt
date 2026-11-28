package xyz.junproject.backend.content.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PathGuardTest {

    @Test
    fun `denylist 최상위는 숨김`() {
        assertTrue(PathGuard.isHidden("docs/logging.md"))
        assertTrue(PathGuard.isHidden("templates/log.md"))
        assertTrue(PathGuard.isHidden("docs/plans/2026-10-01/x/log.md"))
    }

    @Test
    fun `공부 폴더와 루트 직속 파일은 노출`() {
        assertFalse(PathGuard.isHidden("cs/systems/lsm-tree/1-question.md"))
        assertFalse(PathGuard.isHidden("project/study-note-deploy-system/backend/issue1/README.md"))
        assertFalse(PathGuard.isHidden("reference/organize-guide.md"))
        assertFalse(PathGuard.isHidden("index.md"))       // 루트 직속 — 세그먼트=파일명
        assertFalse(PathGuard.isHidden("README.md"))
    }

    @Test
    fun `denylist와 같은 접두 파일명은 숨기지 않는다`() {
        // 최상위 세그먼트가 정확히 폴더명일 때만 숨김 — 유사 파일명은 오탐 금지
        assertFalse(PathGuard.isHidden("docs.md"))
        assertFalse(PathGuard.isHidden("templates-guide.md"))
    }

    @Test
    fun `숨김 판정은 안전성 판정과 독립`() {
        // isHidden은 denylist만 본다(트래버설·확장자 검증은 isSafeMarkdownPath 소관)
        assertTrue(PathGuard.isSafeMarkdownPath("cs/x.md"))
        assertFalse(PathGuard.isHidden("cs/x.md"))
    }

    // 마디 `.` 과 빈 마디(`//` · 처음 · 끝의 `/`)는 거절한다 — 파일 읽기 · git 이 경로를 정규화하면 `./docs/x.md` 가 숨김 폴더의 글을 가리킨다.
    // 점으로 시작하는 이름(`.hidden-name.md`)이나 이름 안의 점 둘(`a..b.md`)은 마디 `.` · `..` 이 아니다

    @Test
    fun `마디 점과 빈 마디가 든 경로는 안전하지 않다`() {
        val rejected = listOf(
            "./docs/x.md", ".//docs/x.md", "cs/./a.md", "cs//a.md", "./a.md", "docs/./x.md",
            "cs/a.md/", "./", ".", "cs/.", "cs/a/./b.md", "cs/a//b.md", "//cs/a.md", "cs/./../docs/x.md",
        )
        val accepted = rejected.filter { path -> PathGuard.isSafeMarkdownPath(path) }
        assertEquals(emptyList<String>(), accepted, "거절해야 할 경로를 받았다")
    }

    @Test
    fun `점으로 시작하는 이름과 이름 안의 점 둘과 한글 이름과 지금까지 받던 경로는 그대로 받는다`() {
        val accepted = listOf(
            "cs/a.md", "index.md", "cs/.hidden-name.md", "cs/a..b.md", "cs/...md",
            "programmers/힙/01-더 맵게/1-question.md", "cs/lsm-tree/2-summary.md", "cs/systems/lsm-tree/1-question.md",
            "project/study-note-deploy-system/backend/issue1/README.md", "reference/organize-guide.md", "README.md",
            "docs.md", "templates-guide.md",
        )
        val rejected = accepted.filterNot { path -> PathGuard.isSafeMarkdownPath(path) }
        assertEquals(emptyList<String>(), rejected, "받아야 할 경로를 거절했다")
    }

    @Test
    fun `지금까지 거절하던 경로는 그대로 거절한다`() {
        val rejected = listOf(
            "../../../etc/passwd", "cs/../../secret.md", "/etc/passwd.md", "~/x.md", ".git/config.md", "cs/.git/x.md",
            "cs/lsm-tree/2-summary.txt", "", "cs/..", "../x.md",
        )
        val accepted = rejected.filter { path -> PathGuard.isSafeMarkdownPath(path) }
        assertEquals(emptyList<String>(), accepted, "거절하던 경로를 받았다")
    }
}
