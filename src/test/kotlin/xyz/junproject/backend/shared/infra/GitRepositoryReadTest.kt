package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.time.Duration

/**
 * 명세 23 — 판을 지정한 읽기(`readFileAt`)는 git 이 "그 판에 그 파일이 없다 · 그 판이 없다"고 답한 경우만 없음(FileNotFoundException)이다.
 * 그 밖의 실패(저장소가 아님 · 다른 오류 · 시간 초과)는 다른 예외(장애). 요청 중의 읽기(`readFileAt` · `currentHead`)는 짧은 시간 제한으로 끝나고,
 * 내용 동기화의 제한은 그대로다.
 * 실행 이미지(JDK)에는 git 이 없다 — 가짜 `git`(셸 스크립트)을 만들어 GitRepository 의 git 실행 파일로 준다. 문구는 git 2.43 의 C 로케일 그대로.
 */
class GitRepositoryReadTest {
    @TempDir lateinit var temp: Path

    private val sha = "1111111111111111111111111111111111111111"
    private val path = "cs/os/process/1-question.md"

    private val calls by lazy { File(temp.toFile(), "calls.log") }

    /** [body] 를 하는 가짜 git — 불릴 때마다 인자를 calls.log 에 남긴다 */
    private fun fakeGit(body: String): String {
        val script = File(temp.toFile(), "git")
        script.writeText("#!/bin/sh\necho \"\$*\" >> '${calls.absolutePath}'\n$body\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun repository(body: String, timeout: Duration = Duration.ofMillis(300)): GitRepository {
        val repoDir = File(temp.toFile(), "repo").also { File(it, ".git").mkdirs() }
        return GitRepository(repoDir, fakeGit(body), timeout)
    }

    private fun assertCalled() = assertTrue(calls.exists() && calls.readText().isNotBlank(), "가짜 git 이 불리지 않았다")

    private fun failureOf(work: () -> Unit): Throwable = assertThrows(Throwable::class.java) { work() }

    @Test
    fun `있는 파일은 그 판을 지정해 읽어 글을 돌려준다`() {
        val git = repository("printf '%s' 'FILE-BODY'")
        assertEquals("FILE-BODY", git.readFileAt(sha, path).trim())
        // 판 표시는 커밋으로 묶어 읽는다 — 폴더의 tree · 글의 blob 을 판으로 주면 그 안의 숨김 글이 읽히기 때문이다(GitRepositoryReadAtObjectTest)
        assertTrue(calls.readText().contains("show") && calls.readText().contains("$sha^{commit}:$path"), calls.readText())
    }

    @Test
    fun `그 판에 그 파일이 없다는 답은 없음(FileNotFoundException)이다`() {
        listOf(
            "fatal: path '$path' does not exist in '$sha'",
            "fatal: path '$path' exists on disk, but not in '$sha'",
            "fatal: invalid object name '$sha'.",
        ).forEach { message ->
            val git = repository("echo \"$message\" >&2; exit 128")
            val failure = failureOf { git.readFileAt(sha, path) }
            assertTrue(failure is FileNotFoundException, "[$message] → ${failure.javaClass.name}")
        }
        assertCalled()
    }

    @Test
    fun `저장소가 아님 같은 다른 git 오류는 없음이 아니라 장애다`() {
        listOf(
            "echo 'fatal: not a git repository (or any of the parent directories): .git' >&2; exit 128",
            "echo 'error: object file .git/objects/ab/cd is empty' >&2; exit 128",
            "echo 'fatal: unable to read tree' >&2; exit 1",
        ).forEach { body ->
            val git = repository(body)
            val failure = failureOf { git.readFileAt(sha, path) }
            assertFalse(failure is FileNotFoundException, "[$body] 가 없음으로 바뀌었다")
        }
        assertCalled()
    }

    @Test
    fun `판을 지정한 읽기가 시간 제한을 넘으면 장애로 곧 끝난다`() {
        val git = repository("sleep 5")
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(3), ThrowingSupplier { failureOf { git.readFileAt(sha, path) } })
        assertFalse(failure is FileNotFoundException, "시간 초과가 없음으로 바뀌었다")
        assertCalled()
    }

    @Test
    fun `지금 판을 얻는 읽기도 시간 제한을 넘으면 장애로 곧 끝난다`() {
        val git = repository("sleep 5")
        assertTimeoutPreemptively(Duration.ofSeconds(3), ThrowingSupplier { failureOf { git.currentHead() } })
        assertCalled()
    }

    @Test
    fun `지금 판을 얻는 읽기는 git 의 답을 다듬어 돌려준다`() {
        assertEquals(sha, repository("echo $sha").currentHead())
    }

    @Test
    fun `내용 동기화는 요청 중의 시간 제한을 쓰지 않는다 - 명령마다 제한보다 오래 걸려도 끝까지 간다`() {
        // 명령마다 0.6초 — 요청 중의 제한(0.3초)이면 끊기고, 동기화의 제한이면 끝까지 간다
        val git = repository("sleep 0.6; case \"\$1\" in rev-parse) echo $sha;; esac")
        assertEquals(sha, git.syncToRemoteHead())
        assertTrue(calls.readText().lines().any { it.startsWith("fetch") }, calls.readText())
        assertTrue(calls.readText().lines().any { it.startsWith("reset") }, calls.readText())
        assertThrows(Throwable::class.java) { git.currentHead() }
    }

    @Test
    fun `기본 시간 제한은 5초다`() {
        assertEquals(Duration.ofSeconds(5), GitRepository.REQUEST_TIMEOUT)
    }

    // 요청 중의 읽기는 시간 초과 때 종료 신호 뒤에 기다리지 않는다 — 기다림(기본 5초)이 붙으면 요청 상한(5초)을 넘긴다.
    // 종료 신호를 무시하는 가짜 git 으로 본다(신호에 곧 죽는 가짜로는 기다림이 붙어도 드러나지 않는다). 기다림은 기본값 그대로 둔다.
    private val gitPidFile by lazy { File(temp.toFile(), "git.pid") }
    private val ignoresTermination by lazy {
        "echo \$\$ > '${gitPidFile.absolutePath}'; trap '' TERM; while :; do sleep 0.1; done"
    }

    /** `/proc/<pid>/stat` 이 없거나 좀비(Z)면 돌지 않는다 — 신호가 닿는 틈으로 1초를 둔다 */
    private fun stillRunning(pid: Long): Boolean {
        val deadline = System.currentTimeMillis() + 1000
        while (true) {
            val state = try { File("/proc/$pid/stat").readText().substringAfterLast(")").trim().firstOrNull() } catch (_: Exception) { null }
            if (state == null || state == 'Z') return false
            if (System.currentTimeMillis() >= deadline) return true
            Thread.sleep(50)
        }
    }

    private fun assertEndsWithoutWaiting(read: (GitRepository) -> Unit) {
        val git = repository(ignoresTermination, timeout = Duration.ofMillis(300))
        val started = System.nanoTime()
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { failureOf { read(git) } })
        val tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()

        assertFalse(failure is FileNotFoundException, "시간 초과가 없음으로 바뀌었다")
        assertTrue(failure.message?.contains("timeout") == true, failure.message)
        // 상한(0.3초) + 1초 — 종료 신호 뒤에 기다림(5초)이 붙으면 넘는다
        assertTrue(tookMillis < 1300, "요청 중의 읽기가 시간 초과 뒤에 기다렸다: ${tookMillis}ms")
        assertTrue(gitPidFile.exists(), "가짜 git 이 pid 를 남기지 않았다")
        assertFalse(stillRunning(gitPidFile.readText().trim().toLong()), "종료 신호를 무시한 가짜 git 이 남았다")
    }

    @Test
    fun `판을 지정한 읽기는 종료 신호를 무시하는 git 도 시간 초과 뒤 기다리지 않고 끊는다`() {
        assertEndsWithoutWaiting { git -> git.readFileAt(sha, path) }
    }

    @Test
    fun `지금 판을 얻는 읽기는 종료 신호를 무시하는 git 도 시간 초과 뒤 기다리지 않고 끊는다`() {
        assertEndsWithoutWaiting { git -> git.currentHead() }
    }
}
