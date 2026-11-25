package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import xyz.junproject.backend.sync.usecase.HistoryState
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * 동기화 명령의 시간 초과 처리(명세 "H0 리뷰의 반영" 1 · 2).
 * - 상한을 넘기면 git 을 곧바로 강제로 끊지 않는다 — 먼저 종료 신호(SIGTERM)를 보내 git 이 잠금(`.git/shallow.lock`)을 스스로 지우게 하고,
 *   [GitRepository.TERMINATION_GRACE](기본 5초 — 테스트는 짧게 주입) 안에 끝나지 않은 것만(그 아래 프로세스 포함) 강제로 끊는다.
 * - 깊게 받기(`fetch --unshallow`)는 평소 명령의 상한이 아니라 [GitRepository.DEEPEN_TIMEOUT](기본 300초)을 쓴다. 그 밖의 명령은 평소 상한 그대로.
 * - 시간 초과의 반환은 지금의 계약 그대로 — 깊게 받기의 시간 초과는 평소의 동기화를 계속하고 problem 에 timeout, 평소 명령의 시간 초과는 장애(IllegalStateException).
 * 가짜 git 은 셸 스크립트다. 프로세스가 남았는지는 리눅스의 `/proc/<pid>/stat` 으로 본다(거둬지지 않은 좀비는 남지 않은 것으로 센다).
 */
class GitRepositoryTimeoutTest {
    @TempDir lateinit var temp: Path

    private val sha = "4444444444444444444444444444444444444444"

    private val calls by lazy { File(temp.toFile(), "calls.log") }
    private val repoDir by lazy { File(temp.toFile(), "repo") }
    private val lockFile by lazy { File(repoDir, ".git/shallow.lock") }
    private val signalRecord by lazy { File(temp.toFile(), "signal.log") }
    private val gitPidFile by lazy { File(temp.toFile(), "git.pid") }
    private val childPidFile by lazy { File(temp.toFile(), "child.pid") }

    /** 불릴 때마다 인자를 calls.log 에 남기고 `case "$*"` 의 [branches] 를 하는 가짜 git. `rev-parse HEAD` 는 [sha] */
    private fun fakeGit(vararg branches: String): String {
        val script = File(temp.toFile(), "git")
        script.writeText(
            "#!/bin/sh\necho \"\$*\" >> '${calls.absolutePath}'\ncase \"\$*\" in\n" + branches.joinToString("\n") +
                "\n  \"rev-parse HEAD\") echo $sha;;\nesac\n"
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun repository(
        vararg branches: String,
        syncTimeout: Duration = Duration.ofSeconds(20),
        deepenTimeout: Duration = Duration.ofSeconds(20),
        terminationGrace: Duration = Duration.ofSeconds(3),
    ): GitRepository {
        File(repoDir, ".git").mkdirs()
        return GitRepository(
            repoDir, fakeGit(*branches),
            requestTimeout = Duration.ofMillis(300),
            syncTimeout = syncTimeout,
            deepenTimeout = deepenTimeout,
            terminationGrace = terminationGrace,
        )
    }

    private fun calledLines(): List<String> = if (calls.exists()) calls.readLines().filter { it.isNotBlank() } else emptyList()

    private val shallowAnswersTrue = "  \"rev-parse --is-shallow-repository\") echo true;;"

    /** 프로세스가 아직 돌고 있는가 — `/proc/<pid>/stat` 이 없거나 좀비(Z)면 돌지 않는다 */
    private fun isRunning(pid: Long): Boolean {
        val stat = File("/proc/$pid/stat")
        val text = try { stat.readText() } catch (_: Exception) { return false }
        val state = text.substringAfterLast(")").trim().firstOrNull()
        return state != null && state != 'Z'
    }

    /** 신호가 닿는 데 걸리는 짧은 틈을 둔다 — [limitMillis] 안에 끝나면 거짓 */
    private fun stillRunningAfter(pid: Long, limitMillis: Long = 1500): Boolean {
        val deadline = System.currentTimeMillis() + limitMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(pid)) return false
            Thread.sleep(50)
        }
        return isRunning(pid)
    }

    private fun pidIn(file: File): Long {
        assertTrue(file.exists(), "가짜 git 이 pid 를 남기지 않았다: ${file.name}")
        return file.readText().trim().toLong()
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val value = block()
        return value to Duration.ofNanos(System.nanoTime() - started).toMillis()
    }

    // ---- 1. 시간 초과 때 먼저 정중히 끝낸다 ----

    @Test
    fun `깊게 받기가 시간 초과면 git 은 먼저 종료 신호를 받고 스스로 잠금을 지운 뒤 끝나며 동기화는 계속된다`() {
        val git = repository(
            shallowAnswersTrue,
            "  \"fetch --unshallow origin main\") echo \$\$ > '${gitPidFile.absolutePath}'; : > '${lockFile.absolutePath}'; " +
                "trap 'echo TERM > \"${signalRecord.absolutePath}\"; rm -f \"${lockFile.absolutePath}\"; exit 143' TERM; " +
                "while :; do sleep 0.1; done;;",
            deepenTimeout = Duration.ofMillis(500),
            terminationGrace = Duration.ofSeconds(3),
        )
        val (head, tookMillis) = timed { assertTimeoutPreemptively(Duration.ofSeconds(8), ThrowingSupplier { git.syncToRemoteHead() }) }

        assertEquals(sha, head)
        assertTrue(signalRecord.exists(), "git 이 종료 신호(TERM)를 받지 못했다 — 곧바로 강제로 끊겼다")
        assertEquals("TERM", signalRecord.readText().trim())
        assertFalse(lockFile.exists(), "종료 신호를 받은 git 이 지울 잠금 파일이 남았다")
        assertFalse(stillRunningAfter(pidIn(gitPidFile)), "가짜 git 이 남았다")
        // 종료 신호에 곧 끝났으면 기다림(3초)을 다 채우지 않는다
        assertTrue(tookMillis < 2500, "종료 신호에 곧 끝났는데 기다림을 다 채웠다: ${tookMillis}ms")
        assertEquals(
            listOf("fetch origin main", "reset --hard origin/main", "rev-parse HEAD"),
            calledLines().dropWhile { it != "fetch --unshallow origin main" }.drop(1),
            "깊게 받기의 시간 초과 뒤에 평소의 동기화가 그대로 돌아야 한다: ${calledLines()}",
        )
        val state = git.historyState()
        assertEquals(true, state.shallow)
        assertTrue(state.problem?.contains("timeout") == true, "시간 초과가 드러나지 않는다: ${state.problem}")
    }

    @Test
    fun `종료 신호를 무시하는 git 은 기다림 뒤에 강제로 끊기고 그 아래 프로세스와 함께 남지 않는다`() {
        val git = repository(
            shallowAnswersTrue,
            "  \"fetch --unshallow origin main\") echo \$\$ > '${gitPidFile.absolutePath}'; trap '' TERM; " +
                "sleep 30 & echo \$! > '${childPidFile.absolutePath}'; " +
                "while :; do sleep 0.1; done;;",
            deepenTimeout = Duration.ofMillis(500),
            terminationGrace = Duration.ofMillis(1500),
        )
        val (head, tookMillis) = timed { assertTimeoutPreemptively(Duration.ofSeconds(8), ThrowingSupplier { git.syncToRemoteHead() }) }

        assertEquals(sha, head)
        // 상한(0.5초) + 기다림(1.5초) — 기다리지 않고 곧바로 끊었다면 0.5초 남짓
        assertTrue(tookMillis >= 1800, "종료 신호 뒤에 기다리지 않고 끊었다: ${tookMillis}ms")
        assertTrue(tookMillis < 5000, "기다림을 넘어서도 끝나지 않았다: ${tookMillis}ms")
        assertFalse(stillRunningAfter(pidIn(gitPidFile)), "종료 신호를 무시한 git 이 남았다")
        assertFalse(stillRunningAfter(pidIn(childPidFile)), "종료 신호를 무시한 git 의 아래 프로세스가 남았다")
        val state = git.historyState()
        assertEquals(true, state.shallow)
        assertTrue(state.problem?.contains("timeout") == true, "시간 초과가 드러나지 않는다: ${state.problem}")
        assertTrue(calledLines().containsAll(listOf("fetch origin main", "reset --hard origin/main")), calledLines().toString())
    }

    @Test
    fun `git 이 종료 신호에 끝나도 신호를 무시한 그 아래 프로세스는 남지 않는다`() {
        val git = repository(
            shallowAnswersTrue,
            "  \"fetch --unshallow origin main\") echo \$\$ > '${gitPidFile.absolutePath}'; " +
                "(trap '' TERM; exec sleep 30) & echo \$! > '${childPidFile.absolutePath}'; " +
                "trap 'echo TERM > \"${signalRecord.absolutePath}\"; exit 143' TERM; " +
                "while :; do sleep 0.1; done;;",
            deepenTimeout = Duration.ofMillis(500),
            terminationGrace = Duration.ofMillis(1500),
        )
        val head = assertTimeoutPreemptively(Duration.ofSeconds(8), ThrowingSupplier { git.syncToRemoteHead() })

        assertEquals(sha, head)
        assertEquals("TERM", signalRecord.takeIf { it.exists() }?.readText()?.trim(), "git 이 종료 신호(TERM)를 받지 못했다")
        assertFalse(stillRunningAfter(pidIn(gitPidFile)), "가짜 git 이 남았다")
        assertFalse(stillRunningAfter(pidIn(childPidFile)), "git 이 끝난 뒤 그 아래 프로세스가 남았다")
        assertTrue(git.historyState().problem?.contains("timeout") == true, git.historyState().toString())
    }

    @Test
    fun `평소 명령의 시간 초과도 먼저 종료 신호를 보내고 동기화는 그 문구를 실은 장애로 끝난다`() {
        val git = repository(
            "  \"rev-parse --is-shallow-repository\") echo false;;",
            "  \"fetch origin main\") echo \$\$ > '${gitPidFile.absolutePath}'; " +
                "trap 'echo TERM > \"${signalRecord.absolutePath}\"; exit 143' TERM; while :; do sleep 0.1; done;;",
            syncTimeout = Duration.ofMillis(500),
        )
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(8), ThrowingSupplier {
            assertThrows(IllegalStateException::class.java) { git.syncToRemoteHead() }
        })
        assertTrue(failure.message?.contains("timeout") == true, failure.message)
        assertEquals("TERM", signalRecord.takeIf { it.exists() }?.readText()?.trim(), "평소 명령의 git 이 종료 신호(TERM)를 받지 못했다")
        assertFalse(stillRunningAfter(pidIn(gitPidFile)), "가짜 git 이 남았다")
        assertFalse(calledLines().any { it.startsWith("reset") }, "받기가 시간 초과인데 reset 했다: ${calledLines()}")
    }

    // ---- 2. 깊게 받기의 시간 상한이 따로다 ----

    @Test
    fun `깊게 받기는 평소 상한보다 오래 걸려도 깊게 받기의 상한 안이면 성공한다`() {
        val git = repository(
            shallowAnswersTrue,
            "  \"fetch --unshallow origin main\") sleep 1;;",
            syncTimeout = Duration.ofMillis(400),
            deepenTimeout = Duration.ofSeconds(5),
        )
        assertEquals(sha, assertTimeoutPreemptively(Duration.ofSeconds(8), ThrowingSupplier { git.syncToRemoteHead() }))
        assertEquals(HistoryState(shallow = false, problem = null), git.historyState())
    }

    @Test
    fun `깊게 받기가 깊게 받기의 상한을 넘으면 평소 상한이 넉넉해도 시간 초과다`() {
        val git = repository(
            shallowAnswersTrue,
            "  \"fetch --unshallow origin main\") exec sleep 5;;",
            syncTimeout = Duration.ofSeconds(20),
            deepenTimeout = Duration.ofMillis(500),
        )
        val (head, tookMillis) = timed { assertTimeoutPreemptively(Duration.ofSeconds(8), ThrowingSupplier { git.syncToRemoteHead() }) }
        assertEquals(sha, head)
        assertTrue(tookMillis < 4000, "깊게 받기의 상한(0.5초)에서 끊지 않았다: ${tookMillis}ms")
        val state = git.historyState()
        assertEquals(true, state.shallow)
        assertTrue(state.problem?.contains("timeout") == true, "시간 초과가 드러나지 않는다: ${state.problem}")
    }

    @Test
    fun `평소의 받기는 깊게 받기의 상한이 넉넉해도 평소 상한에서 끊긴다`() {
        val git = repository(
            "  \"rev-parse --is-shallow-repository\") echo false;;",
            "  \"fetch origin main\") exec sleep 5;;",
            syncTimeout = Duration.ofMillis(500),
            deepenTimeout = Duration.ofSeconds(20),
        )
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(4), ThrowingSupplier {
            assertThrows(IllegalStateException::class.java) { git.syncToRemoteHead() }
        })
        assertTrue(failure.message?.contains("timeout") == true, failure.message)
        assertTrue(failure.message?.contains("fetch origin main") == true, failure.message)
    }

    @Test
    fun `얕은지 묻는 명령은 깊게 받기의 상한이 넉넉해도 평소 상한에서 끊긴다`() {
        val git = repository(
            "  \"rev-parse --is-shallow-repository\") exec sleep 5;;",
            syncTimeout = Duration.ofMillis(500),
            deepenTimeout = Duration.ofSeconds(20),
        )
        assertEquals(sha, assertTimeoutPreemptively(Duration.ofSeconds(4), ThrowingSupplier { git.syncToRemoteHead() }))
        val state = git.historyState()
        assertNull(state.shallow)
        assertTrue(state.problem?.contains("timeout") == true, state.toString())
    }

    @Test
    fun `깊게 받기의 기본 상한은 300초이고 종료 신호 뒤의 기본 기다림은 5초다`() {
        assertEquals(Duration.ofSeconds(300), GitRepository.DEEPEN_TIMEOUT)
        assertEquals(Duration.ofSeconds(5), GitRepository.TERMINATION_GRACE)
    }
}
