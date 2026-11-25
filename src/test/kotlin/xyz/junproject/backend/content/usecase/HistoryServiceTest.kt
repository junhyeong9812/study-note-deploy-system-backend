package xyz.junproject.backend.content.usecase

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 최근 변경은 동기화가 끝날 때 한 번 계산해 쥔다 — 요청마다 git 을 띄우지 않는다(명세 7절). 재시작 뒤 쥔 것이 없으면 첫 요청이 지금 판으로 만든다.
 * - 동기화 뒤의 계산이 실패하면 던지지 않고(동기화를 실패로 만들지 않는다) WARN 을 남기며 앞의 것을 그대로 쥔다.
 * - 쥔 것이 없을 때의 계산이 실패하면 장애(IllegalStateException)로 — 빈 목록을 내지 않고, 실패를 쥐지도 않는다.
 * - 상한: 커밋 30 · 훑기 200 · 커밋당 글 50 · 글 하나의 변경 100.
 */
class HistoryServiceTest {
    private val git = mockk<NoteSourcePort>()
    private val requestLog = mockk<RequestLog>(relaxed = true)
    private val service = HistoryService(git, requestLog)

    private fun commitOf(sha: String) = CommitChanges(sha, "2026-11-25T10:00:00+09:00", "title $sha", listOf(ChangedDoc("cs/a/2-summary.md", ChangeKind.MODIFIED)), 0)

    @Test
    fun `상한은 커밋 30 훑기 200 커밋당 글 50 글 하나의 변경 100 이다`() {
        assertEquals(30, HistoryService.RECENT_COMMIT_LIMIT)
        assertEquals(200, HistoryService.RECENT_SCAN_LIMIT)
        assertEquals(50, HistoryService.DOCS_PER_COMMIT_LIMIT)
        assertEquals(100, HistoryService.REVISION_LIMIT)
    }

    @Test
    fun `동기화 뒤에 한 번 계산한 최근 변경을 요청마다 git 을 띄우지 않고 내준다`() {
        val commits = listOf(commitOf("sha1"))
        every { git.recentChanges(30, 200, 50) } returns commits

        service.rebuild("req-sync", "sha1")
        repeat(3) { assertEquals(HistoryService.RecentChanges("sha1", commits), service.recent("req-$it")) }

        verify(exactly = 1) { git.recentChanges(30, 200, 50) }
        verify(exactly = 0) { git.currentHead() }
    }

    @Test
    fun `다음 동기화가 끝나면 새 판의 최근 변경으로 바뀐다`() {
        every { git.recentChanges(any(), any(), any()) } returnsMany listOf(listOf(commitOf("sha1")), listOf(commitOf("sha2"), commitOf("sha1")))
        service.rebuild("req-sync-1", "sha1")
        service.rebuild("req-sync-2", "sha2")
        val recent = service.recent("req")
        assertEquals("sha2", recent.commitSha)
        assertEquals(listOf("sha2", "sha1"), recent.commits.map { it.sha })
    }

    @Test
    fun `쥔 것이 없으면 첫 요청이 지금 판으로 만들고 그 뒤로는 쥔 것을 내준다`() {
        every { git.currentHead() } returns "head1"
        every { git.recentChanges(30, 200, 50) } returns listOf(commitOf("head1"))

        assertEquals("head1", service.recent("req-1").commitSha)
        assertEquals("head1", service.recent("req-2").commitSha)
        verify(exactly = 1) { git.recentChanges(30, 200, 50) }
    }

    @Test
    fun `동기화 뒤의 계산이 실패하면 던지지 않고 경고를 남기며 앞의 최근 변경을 그대로 쥔다`() {
        every { git.recentChanges(any(), any(), any()) } returns listOf(commitOf("sha1")) andThenThrows IllegalStateException("git timeout(5000ms): log")
        service.rebuild("req-sync-1", "sha1")

        assertDoesNotThrow { service.rebuild("req-sync-2", "sha2") }

        assertEquals("sha1", service.recent("req").commitSha)
        verify { requestLog.log("req-sync-2", match { it.contains("git timeout") }, "warning") }
    }

    @Test
    fun `쥔 것이 없을 때 계산이 실패하면 빈 목록이 아니라 장애이고 실패를 쥐지 않아 다음 요청이 다시 계산한다`() {
        every { git.currentHead() } returns "head1"
        every { git.recentChanges(any(), any(), any()) } throws IllegalStateException("git failed") andThen listOf(commitOf("head1"))

        assertThrows(IllegalStateException::class.java) { service.recent("req-1") }
        assertEquals(listOf("head1"), service.recent("req-2").commits.map { it.sha })
    }

    @Test
    fun `글 하나의 변경 목록은 상한 100 으로 읽고 없음과 장애를 그대로 올린다`() {
        val history = DocHistory(listOf(DocRevision("sha1", "2026-11-25T10:00:00+09:00", "title", "cs/a/2-summary.md")), 0)
        every { git.documentHistory("cs/a/2-summary.md", 100) } returns history
        every { git.documentHistory("cs/none/2-summary.md", 100) } throws java.io.FileNotFoundException("cs/none/2-summary.md")
        every { git.documentHistory("cs/broken/2-summary.md", 100) } throws IllegalStateException("git failed")

        assertEquals(history, service.documentHistory("req", "cs/a/2-summary.md"))
        assertThrows(java.io.FileNotFoundException::class.java) { service.documentHistory("req", "cs/none/2-summary.md") }
        assertThrows(IllegalStateException::class.java) { service.documentHistory("req", "cs/broken/2-summary.md") }
    }
}
