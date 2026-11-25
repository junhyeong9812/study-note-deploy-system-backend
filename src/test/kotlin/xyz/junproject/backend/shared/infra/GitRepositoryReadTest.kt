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
        assertTrue(calls.readText().contains("show") && calls.readText().contains("$sha:$path"), calls.readText())
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
}
