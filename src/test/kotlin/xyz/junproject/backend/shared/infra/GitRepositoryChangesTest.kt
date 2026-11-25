package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 이력 읽기의 git 호출이 지켜야 할 것 — 가짜 git(셸 스크립트)으로.
 * - 사용자가 준 경로는 git 의 인자에서 언제나 `--` 뒤에 온다(옵션으로 읽히지 않는다).
 * - 최근 변경과 글 하나의 이력은 요청 중의 읽기다: 요청 상한([GitRepository] 의 requestTimeout)에서 끊고, 종료 신호를 무시하는 git 도 기다리지 않는다.
 * - git 의 실패 · 시간 초과는 빈 목록이 아니라 IllegalStateException(경로가 503 으로 답한다).
 * - 글 하나의 이력 읽기는 함께 띄우는 git 의 수에 상한이 있다 — 넘는 요청은 기다리지 않고 IllegalStateException.
 */
class GitRepositoryChangesTest {
    @TempDir lateinit var temp: Path

    private val repoDir by lazy { File(temp.toFile(), "repo").also { File(it, ".git").mkdirs() } }
    private val argumentLog by lazy { File(temp.toFile(), "arguments.log") }
    private val gitPidFile by lazy { File(temp.toFile(), "git.pid") }

    /** 불릴 때마다 인자를 한 줄에 하나씩, 호출의 끝에 `@@` 를 적고 [body] 를 하는 가짜 git */
    private fun fakeGit(body: String): String {
        val script = File(temp.toFile(), "git")
        script.writeText(
            "#!/bin/sh\nfor argument in \"\$@\"; do printf '%s\\n' \"\$argument\" >> '${argumentLog.absolutePath}'; done\n" +
                "echo @@ >> '${argumentLog.absolutePath}'\n$body\n"
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun repository(body: String, requestTimeout: Duration = Duration.ofSeconds(3), concurrency: Int = 4) =
        GitRepository(
            repoDir, fakeGit(body), requestTimeout = requestTimeout, syncTimeout = Duration.ofSeconds(20),
            historyReadConcurrency = concurrency,
        )

    /** 호출마다의 인자 목록 */
    private fun invocations(): List<List<String>> {
        if (!argumentLog.exists()) return emptyList()
        val calls = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        argumentLog.readLines().forEach { line ->
            if (line == "@@") { calls += current; current = mutableListOf() } else current += line
        }
        return calls
    }

    /** 경로가 작업 트리에 있는 것처럼 — 구현이 작업 트리로 있는지를 보든 git 에 묻든 git 까지 가게 한다 */
    private fun placeInWorkingTree(path: String) {
        val file = File(repoDir, path)
        file.parentFile.mkdirs()
        file.writeText("# note\n")
    }

    private fun isRunning(pid: Long): Boolean {
        val state = try { File("/proc/$pid/stat").readText().substringAfterLast(")").trim().firstOrNull() } catch (_: Exception) { null }
        return state != null && state != 'Z'
    }

    @Test
    fun `글 하나의 이력을 읽을 때 사용자가 준 경로는 git 의 인자에서 언제나 -- 뒤에 온다`() {
        val optionLike = "--output=${File(temp.toFile(), "pwn.md").absolutePath}"
        val git = repository("exit 0")

        for (path in listOf(optionLike, "-n.md", "cs/a/2-summary.md")) {
            placeInWorkingTree(path)
            argumentLog.delete()
            try { git.documentHistory(path, 100) } catch (_: Exception) { }
            val calls = invocations()
            val withPath = calls.filter { arguments -> arguments.any { it == path } }
            assertTrue(withPath.isNotEmpty(), "경로를 git 에 넘기지 않았다(검증이 없는 테스트가 된다): $path · $calls")
            withPath.forEach { arguments ->
                val separator = arguments.indexOf("--")
                assertTrue(separator >= 0, "경로를 넘긴 호출에 -- 가 없다: $arguments")
                val beforeSeparator = arguments.take(separator)
                assertFalse(beforeSeparator.any { it == path || it.startsWith(path) }, "경로가 -- 앞에 왔다: $arguments")
            }
        }
    }

    @Test
    fun `글 하나의 이력 읽기는 요청 상한에서 끊고 종료 신호를 무시하는 git 도 기다리지 않으며 장애로 끝난다`() {
        placeInWorkingTree("cs/a/2-summary.md")
        val git = repository(
            "echo \$\$ > '${gitPidFile.absolutePath}'; trap '' TERM; while :; do sleep 0.1; done",
            requestTimeout = Duration.ofMillis(300),
        )
        val started = System.nanoTime()
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier {
            assertThrows(Throwable::class.java) { git.documentHistory("cs/a/2-summary.md", 100) }
        })
        val tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()

        assertTrue(failure is IllegalStateException, "시간 초과가 장애(IllegalStateException)가 아니다: $failure")
        assertTrue(failure.message?.contains("timeout") == true, failure.message)
        assertTrue(tookMillis < 1300, "요청 상한(0.3초) 뒤에 기다렸다: ${tookMillis}ms")
        Thread.sleep(200)
        assertFalse(isRunning(gitPidFile.readText().trim().toLong()), "가짜 git 이 남았다")
    }

    @Test
    fun `최근 변경의 계산도 요청 상한에서 끊고 장애로 끝난다`() {
        val git = repository("exec sleep 5", requestTimeout = Duration.ofMillis(300))
        val started = System.nanoTime()
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier {
            assertThrows(Throwable::class.java) { git.recentChanges(30, 200, 50) }
        })
        assertTrue(failure is IllegalStateException, "시간 초과가 장애(IllegalStateException)가 아니다: $failure")
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1300, "요청 상한에서 끊지 않았다")
    }

    @Test
    fun `git 이 실패하면 최근 변경도 글 하나의 이력도 빈 목록이 아니라 장애다`() {
        placeInWorkingTree("cs/a/2-summary.md")
        val git = repository(
            "echo 'error: object file .git/objects/ab/cdef is empty' >&2; echo 'fatal: loose object abcdef is corrupt' >&2; exit 128"
        )
        val recentFailure = assertThrows(Throwable::class.java) { git.recentChanges(30, 200, 50) }
        assertTrue(recentFailure is IllegalStateException, "최근 변경의 git 실패가 장애가 아니다: $recentFailure")
        val historyFailure = assertThrows(Throwable::class.java) { git.documentHistory("cs/a/2-summary.md", 100) }
        assertTrue(historyFailure is IllegalStateException, "글 하나의 이력의 git 실패가 장애가 아니다(없음 · 빈 목록이 되면 안 된다): $historyFailure")
        assertFalse(historyFailure is FileNotFoundException)
    }

    @Test
    fun `글 하나의 이력 읽기는 함께 띄우는 git 의 수가 상한을 넘지 않고 넘친 요청은 기다리지 않고 장애로 끝난다`() {
        placeInWorkingTree("cs/a/2-summary.md")
        val events = File(temp.toFile(), "events.log")
        val git = repository(
            "echo \"start \$(date +%s%N)\" >> '${events.absolutePath}'; sleep 1; echo \"end \$(date +%s%N)\" >> '${events.absolutePath}'",
            requestTimeout = Duration.ofSeconds(5),
            concurrency = 2,
        )
        val pool = Executors.newFixedThreadPool(6)
        val outcomes = try {
            (1..6).map {
                pool.submit(Callable {
                    val started = System.nanoTime()
                    val failure = try { git.documentHistory("cs/a/2-summary.md", 100); null } catch (error: Exception) { error }
                    failure to Duration.ofNanos(System.nanoTime() - started).toMillis()
                })
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 함께 돈 git 의 수: 시작 +1, 끝 -1 을 시각 차례로 더한 최댓값
        val steps = events.readLines().filter { it.isNotBlank() }.map { line ->
            val (kind, time) = line.split(" ")
            time.toLong() to (if (kind == "start") 1 else -1)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        var running = 0
        var most = 0
        steps.forEach { (_, step) -> running += step; most = maxOf(most, running) }
        assertTrue(steps.isNotEmpty(), "가짜 git 이 한 번도 불리지 않았다")
        assertTrue(most <= 2, "함께 띄운 git 이 상한(2)을 넘었다: $most")

        val busy = outcomes.filter { (failure, _) -> failure != null && failure !is FileNotFoundException }
        assertTrue(busy.isNotEmpty(), "상한을 넘친 요청이 하나도 거절되지 않았다 — 기다렸다: $outcomes")
        assertTrue(busy.all { (failure, _) -> failure is IllegalStateException }, "넘친 요청의 실패가 장애(IllegalStateException)가 아니다: $busy")
        assertTrue(busy.all { (_, tookMillis) -> tookMillis < 500 }, "넘친 요청이 기다렸다: $busy")
    }
}
