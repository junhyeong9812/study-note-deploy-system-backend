package xyz.junproject.backend.content

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.sync.GitRepository

class ContentControllerTest {
    private val controller = ContentController(mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `경로 트래버설·비md·절대경로 거부`() {
        assertFalse(controller.isSafe("../../../etc/passwd"))
        assertFalse(controller.isSafe("cs/../../secret.md"))
        assertFalse(controller.isSafe("/etc/passwd.md"))
        assertFalse(controller.isSafe(".git/config.md"))
        assertFalse(controller.isSafe("cs/lsm-tree/2-summary.txt"))
        assertTrue(controller.isSafe("cs/lsm-tree/2-summary.md"))
        assertTrue(controller.isSafe("programmers/힙/01-더 맵게/1-question.md"))
    }

    @Test
    fun `미존재 파일은 not_found 봉투 404`() {
        val git = mockk<GitRepository>(relaxed = true)
        every { git.readFile(any()) } throws java.io.FileNotFoundException("x")
        val response = ContentController(git, mockk(relaxed = true)).getDoc("cs/none/2-summary.md", null)
        assertEquals(404, response.statusCode.value())
        assertEquals(false, (response.body as Map<*, *>)["success"])
    }
}
