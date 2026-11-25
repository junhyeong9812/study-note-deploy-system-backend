package xyz.junproject.backend.sync.usecase

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.junproject.backend.content.usecase.TreeService
import xyz.junproject.backend.indexing.usecase.IndexingService
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.infra.GitRepository
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * 전체 이력으로 바꾸는 것이 실패해도 동기화는 계속되고(사이트의 글이 바뀐다) 실패가 드러난다 — 로그(WARN 이상)와 동기화 상태의 `history`(명세 2절 넷째 줄).
 * 실제 [GitRepository] 에 가짜 git 을 주입하고, 색인 · 트리는 가짜로 둔다. 로그는 실제 RequestLog 를 [LogCapture] 로 모은다.
 * 상태의 꼴: `status()["history"] = { "shallow": true | false | null(모름), "problem": 실패의 문구 | null }`.
 */
class SyncHistoryTest {
    @TempDir lateinit var temp: Path

    private val sha = "3333333333333333333333333333333333333333"
    private val unreachableAnswer = "fatal: unable to access 'https://github.com/junhyeong9812/study-note.git/': Could not resolve host: github.com"
    private val logs = LogCapture()
    private val indexing = mockk<IndexingService>(relaxed = true).also {
        every { it.indexPaths(any(), any(), any(), any()) } returns 1
    }
    private val tree = mockk<TreeService>(relaxed = true)

    @AfterEach fun closeLogs() = logs.close()

    /** `case "$*"` 의 갈래를 하는 가짜 git — `rev-parse HEAD` 는 [sha], `ls-files` 는 글 하나 */
    private fun service(vararg branches: String): SyncService {
        val script = File(temp.toFile(), "git")
        script.writeText(
            "#!/bin/sh\ncase \"\$*\" in\n" + branches.joinToString("\n") +
                "\n  \"rev-parse HEAD\") echo $sha;;\n  *ls-files*) echo 'cs/os/process/2-summary.md';;\nesac\n"
        )
        script.setExecutable(true)
        val repoDir = File(temp.toFile(), "repo").also { File(it, ".git").mkdirs() }
        val git = GitRepository(repoDir, script.absolutePath, requestTimeout = Duration.ofMillis(300), syncTimeout = Duration.ofSeconds(20))
        val service = SyncService(git, indexing, tree, logs.requestLog)
        val field = SyncService::class.java.getDeclaredField("stateFile")
        field.isAccessible = true
        field.set(service, File(temp.toFile(), "state/last_sha"))
        return service
    }

    /** 백그라운드 동기화가 끝날 때까지 기다려 마지막 결과를 돌려준다 */
    private fun syncAndWait(service: SyncService): Map<*, *> {
        assertTrue(service.requestSync("req-history", sha) is SyncService.Decision.Started)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val status = service.status()
            val result = status["last_result"] as Map<*, *>
            if (status["in_flight_sha"] == null && result["outcome"] != null) return result
            Thread.sleep(50)
        }
        fail<Unit>("동기화가 10초 안에 끝나지 않았다: ${service.status()}")
        error("unreachable")
    }

    private fun history(service: SyncService): Map<*, *> {
        val history = service.status()["history"]
        assertTrue(history is Map<*, *>, "동기화 상태에 history 가 없다: ${service.status()}")
        return history as Map<*, *>
    }

    private fun warningLines(): List<String> = logs.lines.filter { it.startsWith("WARN") || it.startsWith("ERROR") }

    @Test
    fun `깊게 받기가 실패해도 동기화는 성공해 글이 바뀌고 실패가 로그와 상태에 보인다`() {
        val service = service(
            "  \"rev-parse --is-shallow-repository\") echo true;;",
            "  \"fetch --unshallow origin main\") echo \"$unreachableAnswer\" >&2; exit 128;;",
        )
        val result = syncAndWait(service)

        assertEquals("ok", result["outcome"], result.toString())
        assertEquals(sha, result["sha"])
        assertEquals(sha, service.status()["last_processed_sha"])
        verify { tree.rebuild(any(), sha) }

        val warned = warningLines().filter { it.contains("Could not resolve host: github.com") }
        assertTrue(warned.isNotEmpty(), "깊게 받기의 실패가 WARN 이상으로 남지 않았다: ${logs.lines}")
        assertTrue(warned.any { it.contains("unshallow") }, "무엇이 실패했는지(깊게 받기) 로그에 없다: $warned")

        val history = history(service)
        assertEquals(true, history["shallow"])
        assertTrue((history["problem"] as? String)?.contains("Could not resolve host: github.com") == true, history.toString())
    }

    @Test
    fun `얕은지 묻는 명령이 실패해도 동기화는 성공하고 그 사실이 로그와 상태에 남는다`() {
        val service = service(
            "  \"rev-parse --is-shallow-repository\") echo 'fatal: not a git repository (or any of the parent directories): .git' >&2; exit 128;;",
        )
        val result = syncAndWait(service)

        assertEquals("ok", result["outcome"], result.toString())
        assertTrue(warningLines().any { it.contains("not a git repository") }, "얕은지 묻기의 실패가 로그에 없다: ${logs.lines}")
        val history = history(service)
        assertNull(history["shallow"])
        assertTrue((history["problem"] as? String)?.contains("not a git repository") == true, history.toString())
    }

    @Test
    fun `깊은 저장소의 동기화는 이력 경고를 남기지 않고 상태는 얕지 않음이다`() {
        val service = service("  \"rev-parse --is-shallow-repository\") echo false;;")
        val result = syncAndWait(service)

        assertEquals("ok", result["outcome"], result.toString())
        val historyWarnings = warningLines().filter { it.contains("req-history") || it.contains("shallow") }
        assertTrue(historyWarnings.isEmpty(), "깊은 저장소인데 경고가 남았다: $historyWarnings")
        val history = history(service)
        assertEquals(false, history["shallow"])
        assertNull(history["problem"])
    }

    @Test
    fun `동기화 전의 상태는 저장소가 얕은지 모름이다`() {
        val service = service("  \"rev-parse --is-shallow-repository\") echo true;;")
        val history = history(service)
        assertTrue(history.containsKey("shallow") && history.containsKey("problem"), history.toString())
        assertNull(history["shallow"])
        assertNull(history["problem"])
    }

    @Test
    fun `평소의 받기가 실패하면 동기화는 실패로 끝나고 마지막 처리 판은 그대로다`() {
        val service = service(
            "  \"rev-parse --is-shallow-repository\") echo false;;",
            "  fetch*) echo \"$unreachableAnswer\" >&2; exit 128;;",
        )
        val result = syncAndWait(service)

        assertEquals("failed", result["outcome"], result.toString())
        assertTrue((result["error"] as? String)?.contains("Could not resolve host: github.com") == true, result.toString())
        assertNull(service.status()["last_processed_sha"])
        assertTrue(logs.lines.any { it.startsWith("ERROR") && it.contains("sync failed") }, logs.lines.toString())
        verify(exactly = 0) { tree.rebuild(any(), any()) }
    }

    @Test
    fun `깊게 받기가 성공해도 아직 얕으면 동기화는 성공하고 로그와 상태에 아직 얕다가 보인다`() {
        val service = service("  \"rev-parse --is-shallow-repository\") echo true;;")
        // 원격이 얕은 사본이면 깊게 받기가 0 으로 끝나도 git 의 얕은 표시가 남는다 — 가짜 git 은 그것을 지우지 않는다
        File(temp.toFile(), "repo/.git/shallow").writeText("$sha\n")
        val result = syncAndWait(service)

        assertEquals("ok", result["outcome"], result.toString())
        verify { tree.rebuild(any(), sha) }
        val warned = warningLines().filter { it.startsWith("WARN") && it.contains("아직 얕다") }
        assertEquals(1, warned.size, "아직 얕다는 WARN 이 한 줄이 아니다: ${logs.lines}")
        val history = history(service)
        assertEquals(true, history["shallow"])
        assertTrue((history["problem"] as? String)?.contains("아직 얕다") == true, history.toString())
    }

    @Test
    fun `잠금 파일이 남아 깊게 받기가 실패해도 동기화는 성공하고 잠금이 남았다는 것이 로그와 상태에 보이며 잠금은 그대로다`() {
        val lockFile = File(temp.toFile(), "repo/.git/shallow.lock")
        val service = service(
            "  \"rev-parse --is-shallow-repository\") echo true;;",
            "  \"fetch --unshallow origin main\") echo \"fatal: Unable to create '${lockFile.absolutePath}': File exists.\" >&2; exit 128;;",
        )
        lockFile.writeText("")
        val result = syncAndWait(service)

        assertEquals("ok", result["outcome"], result.toString())
        verify { tree.rebuild(any(), sha) }
        assertTrue(lockFile.exists(), "남은 잠금 파일을 지웠다")
        assertTrue(warningLines().any { it.startsWith("WARN") && it.contains("잠금 파일이 남아 있다") }, "잠금이 남았다는 WARN 이 없다: ${logs.lines}")
        val history = history(service)
        assertEquals(true, history["shallow"])
        assertTrue((history["problem"] as? String)?.contains("잠금 파일이 남아 있다") == true, history.toString())
    }
}
