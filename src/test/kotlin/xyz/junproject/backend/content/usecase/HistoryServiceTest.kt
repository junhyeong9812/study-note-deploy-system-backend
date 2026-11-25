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
 * - 쥔 것이 없을 때의 계산이 실패하면 장애(IllegalStateException)로 — 빈 목록을 내지 않는다. 그 실패는 짧게 쥔다(아래 절).
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
    fun `쥔 것이 없을 때 계산이 실패하면 빈 목록이 아니라 장애다`() {
        every { git.currentHead() } returns "head1"
        every { git.recentChanges(any(), any(), any()) } throws IllegalStateException("git failed")

        assertThrows(IllegalStateException::class.java) { service.recent("req-1") }
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

    // ---- 첫 요청의 계산은 하나만, 기다리지 않는다 ----
    // 쥔 것이 없을 때 계산이 도는 동안 온 요청은 잠금을 기다리지 않고 곧바로 장애(경로는 503 content_unavailable).
    // 계산이 실패하면 그 실패를 [HistoryService.RECENT_FAILURE_HOLD](기본 5초) 동안 쥔다 — 그동안의 요청은 git 을 띄우지 않고 장애, 지난 뒤의 첫 요청이 다시 계산한다.
    // 동기화의 rebuild 는 계산이 도는 동안에도 기다리지 않고 새 판으로 바꿔 끼우고, 늦게 끝난 첫 요청의 계산이 그것을 덮지 않는다.

    /** 손으로 옮기는 시계 */
    private class MovableClock(var now: java.time.Instant) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
        override fun instant(): java.time.Instant = now
    }

    @Test
    fun `실패를 쥐는 기본 시간은 5초다`() {
        assertEquals(java.time.Duration.ofSeconds(5), HistoryService.RECENT_FAILURE_HOLD)
    }

    @Test
    fun `첫 요청의 계산이 도는 동안 온 요청은 기다리지 않고 곧바로 장애이고 계산은 한 번만 돈다`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        every { git.currentHead() } returns "head1"
        every { git.recentChanges(30, 200, 50) } answers { entered.countDown(); gate.await(); listOf(commitOf("head1")) }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit(java.util.concurrent.Callable { service.recent("req-first") })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "준비: 첫 요청의 계산이 시작되지 않았다")

            repeat(5) { number ->
                val started = System.nanoTime()
                val failure = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), org.junit.jupiter.api.function.ThrowingSupplier {
                    assertThrows(Throwable::class.java) { service.recent("req-$number") }
                })
                val tookMillis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()
                assertTrue(failure is IllegalStateException, "계산 중에 온 요청의 실패가 장애(IllegalStateException)가 아니다: $failure")
                assertTrue(tookMillis < 100, "계산 중에 온 요청이 기다렸다: ${tookMillis}ms")
            }
            verify(exactly = 1) { git.recentChanges(30, 200, 50) }

            gate.countDown()
            assertEquals("head1", first.get(5, java.util.concurrent.TimeUnit.SECONDS).commitSha)
            assertEquals("head1", service.recent("req-after").commitSha)
            verify(exactly = 1) { git.recentChanges(30, 200, 50) }
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `첫 요청의 계산이 실패하면 그 실패를 쥐는 동안 git 을 다시 띄우지 않고 지난 뒤의 첫 요청이 다시 계산한다`() {
        val clock = MovableClock(java.time.Instant.parse("2026-11-26T00:00:00Z"))
        val heldService = HistoryService(git, requestLog, clock, java.time.Duration.ofSeconds(5))
        every { git.currentHead() } returns "head1"
        every { git.recentChanges(30, 200, 50) } throws IllegalStateException("git timeout(5000ms): log") andThen listOf(commitOf("head1"))

        assertThrows(IllegalStateException::class.java) { heldService.recent("req-1") }
        verify(exactly = 1) { git.recentChanges(30, 200, 50) }

        clock.now = clock.now.plusMillis(4_999)
        assertThrows(IllegalStateException::class.java) { heldService.recent("req-2") }
        verify(exactly = 1) { git.recentChanges(30, 200, 50) }

        clock.now = clock.now.plusMillis(1)
        assertEquals("head1", heldService.recent("req-3").commitSha)
        verify(exactly = 2) { git.recentChanges(30, 200, 50) }

        clock.now = clock.now.plusSeconds(60)
        assertEquals("head1", heldService.recent("req-4").commitSha)
        verify(exactly = 2) { git.recentChanges(30, 200, 50) }
    }

    @Test
    fun `동기화의 rebuild 는 첫 요청의 계산이 도는 동안에도 기다리지 않고 새 판으로 바꿔 끼우며 늦게 끝난 계산이 덮지 않는다`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        every { git.currentHead() } returns "head-old"
        every { git.recentChanges(30, 200, 50) } answers {
            if (calls.incrementAndGet() == 1) { entered.countDown(); gate.await(); listOf(commitOf("head-old")) }
            else listOf(commitOf("sha2"), commitOf("head-old"))
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit(java.util.concurrent.Callable { service.recent("req-first") })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "준비: 첫 요청의 계산이 시작되지 않았다")

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2)) { service.rebuild("req-sync", "sha2") }
            assertEquals("sha2", service.recent("req-during").commitSha)

            gate.countDown()
            first.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("sha2", service.recent("req-after").commitSha, "늦게 끝난 첫 요청의 계산이 새 판을 덮었다")
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `첫 요청의 계산이 막혀도 가상 스레드의 실행 자리를 붙잡지 않아 상관없는 작업이 곧바로 돈다`() {
        // 테스트 JVM 은 가상 스레드의 실행 자리를 2 로 좁힌다(build.gradle.kts). 잠금 안에서 git 을 기다리면 그 자리를 붙잡는다(JDK 21)
        val gate = java.util.concurrent.CountDownLatch(1)
        every { git.currentHead() } returns "head1"
        every { git.recentChanges(30, 200, 50) } answers { gate.await(); listOf(commitOf("head1")) }
        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            repeat(6) { number -> executor.submit { runCatching { service.recent("req-virtual-$number") } } }
            Thread.sleep(300)
            val unrelatedRan = java.util.concurrent.CountDownLatch(1)
            executor.submit { unrelatedRan.countDown() }
            assertTrue(unrelatedRan.await(1, java.util.concurrent.TimeUnit.SECONDS), "첫 요청의 계산이 막힌 동안 상관없는 가상 작업이 돌지 못했다")
        } finally {
            gate.countDown()
            executor.shutdown()
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
