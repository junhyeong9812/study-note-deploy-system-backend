package xyz.junproject.backend.usecase

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.infra.RequestLog
import xyz.junproject.backend.infra.GitRepository
import java.nio.file.Files

/** 멱등·single-flight 판정 경로 우선 (spec ⑤ — 거절 경로 먼저). */
class SyncServiceTest {
    private fun newService(lastSha: String? = null): SyncService {
        val stateDir = Files.createTempDirectory("sync-test")
        val stateFile = stateDir.resolve("last_sha")
        lastSha?.let { Files.writeString(stateFile, it) }
        // STATE_FILE은 env 기반이라 리플렉션 대신 임시 파일을 env로 못 주입 —
        // 생성 후 stateFile 필드를 교체한다 (테스트 한정).
        val service = SyncService(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val field = SyncService::class.java.getDeclaredField("stateFile")
        field.isAccessible = true
        field.set(service, stateFile.toFile())
        return service
    }

    @Test
    fun `마지막 처리 SHA와 같으면 duplicate skip`() {
        val service = newService(lastSha = "abc123")
        val decision = service.requestSync("req-1", "abc123")
        assertTrue(decision is SyncService.Decision.Skip && decision.reason == "duplicate")
    }

    @Test
    fun `진행 중 - 같은 SHA는 in_progress skip, 다른 SHA는 busy`() {
        val git = mockk<GitRepository>()
        val gate = java.util.concurrent.CountDownLatch(1)
        every { git.syncToRemoteHead() } answers { gate.await(); "head99" }
        val service = SyncService(git, mockk(relaxed = true), mockk(relaxed = true))
        val field = SyncService::class.java.getDeclaredField("stateFile")
        field.isAccessible = true
        field.set(service, Files.createTempDirectory("t").resolve("s").toFile())

        assertTrue(service.requestSync("req-1", "sha-A") is SyncService.Decision.Started)
        assertTrue(service.requestSync("req-2", "sha-A").let { it is SyncService.Decision.Skip && it.reason == "in_progress" })
        assertTrue(service.requestSync("req-3", "sha-B") is SyncService.Decision.Busy)
        gate.countDown()
    }

    @Test
    fun `성공 시에만 last_sha 전진 - 이후 같은 SHA는 duplicate`() {
        val git = mockk<GitRepository>()
        every { git.syncToRemoteHead() } returns "head77"
        every { git.changedMarkdown(any(), any()) } returns listOf('A' to "cs/x/2-summary.md")
        every { git.allMarkdown() } returns listOf("cs/x/2-summary.md")
        val indexing = mockk<IndexingService>(relaxed = true)
        every { indexing.indexPaths(any(), any(), any(), any()) } returns 3
        val service = SyncService(git, indexing, mockk<RequestLog>(relaxed = true))
        val field = SyncService::class.java.getDeclaredField("stateFile")
        field.isAccessible = true
        field.set(service, Files.createTempDirectory("t").resolve("s").toFile())

        service.requestSync("req-1", "head77")
        Thread.sleep(500)   // 백그라운드 파이프라인 완료 대기
        val decision = service.requestSync("req-2", "head77")
        assertTrue(decision is SyncService.Decision.Skip && decision.reason == "duplicate")
    }
}
