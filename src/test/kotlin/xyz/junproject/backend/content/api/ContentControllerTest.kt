package xyz.junproject.backend.content.api

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.content.usecase.NoteSourcePort

class ContentControllerTest {
    private val controller = ContentController(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

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
        val git = mockk<NoteSourcePort>(relaxed = true)
        every { git.readFile(any()) } throws java.io.FileNotFoundException("x")
        val response = ContentController(git, mockk(relaxed = true), mockk(relaxed = true)).getDoc("cs/none/2-summary.md", null, null)
        assertEquals(404, response.statusCode.value())
        assertEquals(false, (response.body as Map<*, *>)["success"])
    }

    // 명세 23 — 문서 주소로 옛 판을 여는 길(`/api/doc?at=`)도 같은 판을 지정한 읽기를 쓴다: 없음만 404, git 의 장애는 404 가 아니라 오류다

    @Test
    fun `옛 판에 없는 파일이나 없는 판은 404 not_found 다`() {
        val git = mockk<NoteSourcePort>(relaxed = true)
        every { git.readFileAt(any(), any()) } throws java.io.FileNotFoundException("abcdef1:cs/none/2-summary.md")
        val response = ContentController(git, mockk(relaxed = true), mockk(relaxed = true)).getDoc("cs/none/2-summary.md", "abcdef1", null)
        assertEquals(404, response.statusCode.value())
        assertEquals("not_found", ((response.body as Map<*, *>)["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `옛 판을 읽다 git 이 장애면 404 가 아니라 전역 처리기의 500 이다`() {
        val git = mockk<NoteSourcePort>(relaxed = true)
        every { git.readFileAt(any(), any()) } throws IllegalStateException("git timeout")
        val requestLog = mockk<xyz.junproject.backend.shared.infra.RequestLog>(relaxed = true)
        val mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(ContentController(git, mockk(relaxed = true), requestLog))
            .setControllerAdvice(xyz.junproject.backend.shared.api.GlobalErrorHandler(requestLog)).build()
        val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/doc")
            .param("path", "cs/os/process/2-summary.md").param("at", "abcdef1")).andReturn().response
        assertEquals(500, response.status, response.contentAsString)
        assertTrue(response.contentAsString.contains("\"internal\""), response.contentAsString)
    }
}
