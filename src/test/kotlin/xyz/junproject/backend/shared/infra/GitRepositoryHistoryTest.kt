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
 * 내용 저장소를 전체 이력으로 갖는다(명세 8절 H0 · 2절 넷째 줄).
 * - 새로 받을 때 `--depth` 없이 받는다. 평소의 받기도 `--depth` 없이 받는다(`fetch --depth=50` 은 깊은 저장소를 다시 얕게 만든다 — git 2.43 실측).
 * - 이미 있는 저장소는 동기화 때 `rev-parse --is-shallow-repository` 로 묻고, `true` 면 `fetch --unshallow origin main` 으로 깊게 받은 뒤 평소의 동기화를 한다.
 *   `false` 면 깊게 받기를 부르지 않는다(`--unshallow` 는 깊은 저장소에서 `fatal: --unshallow on a complete repository does not make sense` · 128 로 끝난다).
 * - 깊게 받기 · 얕은지 묻기가 실패해도(0 이 아닌 종료 · 시간 초과) 평소의 동기화는 계속되고, 실패는 [HistoryState.problem] 으로 쥔다. 다음 동기화에서 다시 시도한다.
 * - "얕은가"는 동기화가 알아낸 값을 쥔다 — 물어도 git 을 띄우지 않는다. 동기화 전에는 모름(null).
 * 실행 이미지(JDK)에는 git 이 없다 — 가짜 `git`(셸 스크립트)을 주입한다. 문구는 git 2.43 의 C 로케일 그대로.
 */
class GitRepositoryHistoryTest {
    @TempDir lateinit var temp: Path

    private val sha = "2222222222222222222222222222222222222222"
    private val remote = System.getenv("STUDY_NOTE_REMOTE") ?: "https://github.com/junhyeong9812/study-note.git"
    private val unreachableAnswer = "fatal: unable to access 'https://github.com/junhyeong9812/study-note.git/': Could not resolve host: github.com"

    private val calls by lazy { File(temp.toFile(), "calls.log") }
    private val locales by lazy { File(temp.toFile(), "locales.log") }
    private val repoDir by lazy { File(temp.toFile(), "repo") }

    /** 불릴 때마다 인자를 calls.log 에, LC_ALL 을 locales.log 에 남기고 [body] 를 하는 가짜 git */
    private fun fakeGit(body: String): String {
        val script = File(temp.toFile(), "git")
        script.writeText(
            "#!/bin/sh\necho \"\$*\" >> '${calls.absolutePath}'\necho \"\$LC_ALL\" >> '${locales.absolutePath}'\n$body\n"
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    /** `case "$*"` 의 갈래들 — 지정하지 않은 명령은 아무것도 내지 않고 0 으로 끝난다. `rev-parse HEAD` 는 [sha] */
    private fun answers(vararg branches: String): String =
        "case \"\$*\" in\n" + branches.joinToString("\n") + "\n  \"rev-parse HEAD\") echo $sha;;\nesac"

    private fun existingRepository(body: String, syncTimeout: Duration = Duration.ofSeconds(20)): GitRepository {
        File(repoDir, ".git").mkdirs()
        return GitRepository(repoDir, fakeGit(body), requestTimeout = Duration.ofMillis(300), syncTimeout = syncTimeout)
    }

    private fun calledLines(): List<String> = if (calls.exists()) calls.readLines().filter { it.isNotBlank() } else emptyList()

    private val shallowAnswersTrue = "  \"rev-parse --is-shallow-repository\") echo true;;"
    private val shallowAnswersFalse = "  \"rev-parse --is-shallow-repository\") echo false;;"
    private val deepenFailsUnreachable = "  \"fetch --unshallow origin main\") echo \"$unreachableAnswer\" >&2; exit 128;;"

    @Test
    fun `새로 받을 때는 --depth 없이 받고 받은 저장소는 얕지 않다`() {
        repoDir.parentFile.mkdirs()
        // 받기는 저장소 폴더를 만든다 — 받은 뒤의 명령은 그 폴더에서 돈다
        val git = GitRepository(repoDir, fakeGit(answers("  clone*) mkdir -p '${repoDir.absolutePath}/.git';;")), requestTimeout = Duration.ofMillis(300))
        assertEquals(sha, git.syncToRemoteHead())
        assertEquals(listOf("clone $remote ${repoDir.absolutePath}", "rev-parse HEAD"), calledLines())
        assertEquals(HistoryState(shallow = false, problem = null), git.historyState())
    }

    @Test
    fun `이미 깊은 저장소는 얕은지 묻는 명령 하나만 더해지고 깊게 받기를 부르지 않는다`() {
        val git = existingRepository(answers(shallowAnswersFalse))
        assertEquals(sha, git.syncToRemoteHead())
        assertEquals(
            listOf("rev-parse --is-shallow-repository", "fetch origin main", "reset --hard origin/main", "rev-parse HEAD"),
            calledLines(),
        )
        assertEquals(HistoryState(shallow = false, problem = null), git.historyState())
    }

    @Test
    fun `얕은 저장소는 깊게 받은 뒤 평소의 동기화를 하고 얕지 않게 된다`() {
        val git = existingRepository(answers(shallowAnswersTrue))
        assertEquals(sha, git.syncToRemoteHead())
        assertEquals(
            listOf(
                "rev-parse --is-shallow-repository",
                "fetch --unshallow origin main",
                "fetch origin main",
                "reset --hard origin/main",
                "rev-parse HEAD",
            ),
            calledLines(),
        )
        assertEquals(HistoryState(shallow = false, problem = null), git.historyState())
    }

    @Test
    fun `동기화의 어느 명령도 --depth 로 받지 않는다 - 깊어진 저장소를 다시 얕게 만들지 않는다`() {
        val git = existingRepository(answers(shallowAnswersTrue))
        git.syncToRemoteHead()
        val withDepth = calledLines().filter { line -> line.contains("--depth") }
        assertTrue(withDepth.isEmpty(), "--depth 로 받은 명령: $withDepth")
    }

    @Test
    fun `깊게 받기가 실패해도 평소의 동기화는 끝까지 가고 얕다는 것과 실패의 문구를 쥔다`() {
        val git = existingRepository(answers(shallowAnswersTrue, deepenFailsUnreachable))
        assertEquals(sha, git.syncToRemoteHead())
        val lines = calledLines()
        assertEquals(
            listOf("fetch origin main", "reset --hard origin/main", "rev-parse HEAD"),
            lines.dropWhile { it != "fetch --unshallow origin main" }.drop(1),
            "깊게 받기 뒤에 평소의 동기화가 그대로 돌아야 한다: $lines",
        )
        val state = git.historyState()
        assertEquals(true, state.shallow)
        assertTrue(state.problem?.contains("Could not resolve host: github.com") == true, "실패의 문구가 없다: ${state.problem}")
    }

    @Test
    fun `깊게 받기가 시간 제한을 넘어도 동기화는 그 제한에서 끊고 평소의 동기화로 간다`() {
        val git = existingRepository(
            answers(shallowAnswersTrue, "  \"fetch --unshallow origin main\") sleep 5;;"),
            syncTimeout = Duration.ofMillis(700),
        )
        val head = assertTimeoutPreemptively(Duration.ofSeconds(4), ThrowingSupplier { git.syncToRemoteHead() })
        assertEquals(sha, head)
        assertTrue(calledLines().containsAll(listOf("fetch origin main", "reset --hard origin/main")), calledLines().toString())
        val state = git.historyState()
        assertEquals(true, state.shallow)
        assertTrue(state.problem?.contains("timeout") == true, "시간 초과가 드러나지 않는다: ${state.problem}")
    }

    @Test
    fun `깊게 받기에 한 번 실패해도 다음 동기화에서 다시 시도하고 성공하면 실패가 지워진다`() {
        val failedOnce = File(temp.toFile(), "failed-once")
        val deepened = File(temp.toFile(), "deepened")
        val git = existingRepository(answers(
            "  \"rev-parse --is-shallow-repository\") if [ -e '${deepened.absolutePath}' ]; then echo false; else echo true; fi;;",
            "  \"fetch --unshallow origin main\") if [ -e '${failedOnce.absolutePath}' ]; then touch '${deepened.absolutePath}'; " +
                "else touch '${failedOnce.absolutePath}'; echo \"$unreachableAnswer\" >&2; exit 128; fi;;",
        ))

        git.syncToRemoteHead()
        assertEquals(true, git.historyState().shallow)
        assertNotNull(git.historyState().problem)

        git.syncToRemoteHead()
        assertEquals(2, calledLines().count { it == "fetch --unshallow origin main" }, calledLines().toString())
        assertEquals(HistoryState(shallow = false, problem = null), git.historyState())

        git.syncToRemoteHead()
        assertEquals(2, calledLines().count { it == "fetch --unshallow origin main" }, "깊어진 뒤에도 깊게 받기를 불렀다: ${calledLines()}")
    }

    @Test
    fun `얕은지 묻는 명령이 실패하면 깊게 받기를 건너뛰고 평소의 동기화로 가며 모름과 그 문구를 쥔다`() {
        val git = existingRepository(answers(
            "  \"rev-parse --is-shallow-repository\") echo 'fatal: not a git repository (or any of the parent directories): .git' >&2; exit 128;;",
        ))
        assertEquals(sha, git.syncToRemoteHead())
        assertEquals(
            listOf("rev-parse --is-shallow-repository", "fetch origin main", "reset --hard origin/main", "rev-parse HEAD"),
            calledLines(),
        )
        val state = git.historyState()
        assertNull(state.shallow)
        assertTrue(state.problem?.contains("not a git repository") == true, "실패의 문구가 없다: ${state.problem}")
    }

    @Test
    fun `얕은지 묻는 명령이 시간 제한을 넘으면 깊게 받기를 건너뛰고 평소의 동기화로 간다`() {
        val git = existingRepository(
            answers("  \"rev-parse --is-shallow-repository\") sleep 5;;"),
            syncTimeout = Duration.ofMillis(700),
        )
        val head = assertTimeoutPreemptively(Duration.ofSeconds(4), ThrowingSupplier { git.syncToRemoteHead() })
        assertEquals(sha, head)
        assertFalse(calledLines().contains("fetch --unshallow origin main"), calledLines().toString())
        assertTrue(calledLines().containsAll(listOf("fetch origin main", "reset --hard origin/main")), calledLines().toString())
        val state = git.historyState()
        assertNull(state.shallow)
        assertTrue(state.problem?.contains("timeout") == true, "시간 초과가 드러나지 않는다: ${state.problem}")
    }

    @Test
    fun `얕은지 묻는 명령이 참도 거짓도 아닌 답을 내면 모름으로 두고 깊게 받기를 건너뛴다`() {
        val git = existingRepository(answers("  \"rev-parse --is-shallow-repository\") echo '--is-shallow-repository';;"))
        assertEquals(sha, git.syncToRemoteHead())
        assertFalse(calledLines().contains("fetch --unshallow origin main"), calledLines().toString())
        val state = git.historyState()
        assertNull(state.shallow)
        assertNotNull(state.problem)
    }

    @Test
    fun `저장소가 얕은지 물어도 git 을 띄우지 않고 동기화 전에는 모름이다`() {
        val git = existingRepository(answers(shallowAnswersTrue, deepenFailsUnreachable))
        assertEquals(HistoryState(shallow = null, problem = null), git.historyState())
        assertTrue(calledLines().isEmpty(), "동기화 전에 git 을 띄웠다: ${calledLines()}")

        git.syncToRemoteHead()
        val afterSync = calledLines().size
        repeat(3) { git.historyState() }
        assertEquals(afterSync, calledLines().size, "물을 때마다 git 을 띄웠다: ${calledLines()}")
    }

    @Test
    fun `동기화의 명령은 깊게 받기를 포함해 모두 LC_ALL=C 로 돈다`() {
        val git = existingRepository(answers(shallowAnswersTrue))
        git.syncToRemoteHead()
        val localeLines = locales.readLines()
        assertEquals(calledLines().size, localeLines.size)
        assertTrue(calledLines().size >= 5, calledLines().toString())
        assertTrue(localeLines.all { it == "C" }, "LC_ALL 이 C 가 아닌 명령이 있다: $localeLines")
    }

    @Test
    fun `평소의 받기가 실패하면 동기화는 git 의 문구를 실은 장애로 끝난다`() {
        val git = existingRepository(answers(
            shallowAnswersFalse,
            "  \"fetch --unshallow\"*) exit 0;;",
            "  fetch*) echo \"$unreachableAnswer\" >&2; exit 128;;",
        ))
        val failure = assertThrows(IllegalStateException::class.java) { git.syncToRemoteHead() }
        assertTrue(failure.message?.contains("Could not resolve host: github.com") == true, failure.message)
        assertFalse(calledLines().any { it.startsWith("reset") }, "받기가 실패했는데 reset 했다: ${calledLines()}")
    }
}
