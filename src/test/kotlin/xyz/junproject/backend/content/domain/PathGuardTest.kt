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
}
