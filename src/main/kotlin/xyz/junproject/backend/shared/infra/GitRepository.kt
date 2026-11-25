package xyz.junproject.backend.shared.infra

import xyz.junproject.backend.indexing.usecase.DocumentReader

import xyz.junproject.backend.content.domain.PathGuard
import xyz.junproject.backend.content.usecase.CommitInfo
import xyz.junproject.backend.content.usecase.NoteSourcePort

import xyz.junproject.backend.sync.usecase.ShaUnresolvableException

import xyz.junproject.backend.sync.usecase.HistoryState
import xyz.junproject.backend.sync.usecase.SourceControlPort

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** study-note clone 볼륨 관리 — shell git (es-index.md D5-1: diff가 색인 입력의 정본). */
@Component
class GitRepository(
    private val repoDir: File,
    /** git 실행 파일 — 테스트가 가짜 git 으로 바꾼다 */
    private val gitCommand: String = "git",
    /** 요청 중의 읽기(readFileAt · currentHead)의 시간 제한(명세 23 — 기본 5초). 내용 동기화 · 목록 명령은 [syncTimeout] */
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    /** 내용 동기화 · 목록 명령의 시간 제한(명령마다) — 테스트가 짧게 바꾼다 */
    private val syncTimeout: Duration = SYNC_TIMEOUT,
    /** 깊게 받기(`fetch --unshallow`)의 시간 제한 — 평소 명령의 [syncTimeout] 과 따로. 테스트가 짧게 바꾼다 */
    private val deepenTimeout: Duration = DEEPEN_TIMEOUT,
    /** 시간 초과 때 종료 신호를 보낸 뒤 강제로 끊기 전까지 기다리는 시간 — 테스트가 짧게 바꾼다 */
    private val terminationGrace: Duration = TERMINATION_GRACE,
) : SourceControlPort, NoteSourcePort, DocumentReader {
    @Autowired
    constructor() : this(File(System.getenv("REPO_DIR") ?: "/data/study-note/repo"), deepenTimeout = deepenTimeoutFromEnvironment())

    private val remote = System.getenv("STUDY_NOTE_REMOTE")
        ?: "https://github.com/junhyeong9812/study-note.git"

    /** 마지막 동기화가 알아낸 이력의 상태 — 요청 스레드들이 읽는다. 값 하나를 통째로 바꿔 끼운다 */
    @Volatile private var history: HistoryState = HistoryState.UNKNOWN

    /**
     * 내용 저장소를 전체 이력으로 받는다 — `--depth` 로 받지 않는다(깊은 저장소에 `fetch --depth` 를 돌리면 다시 얕아진다).
     * 이미 있는 저장소가 얕으면 평소의 받기 전에 한 번 깊게 받는다. 깊게 받기 · 얕은지 묻기의 실패는 [history] 에 쥐고
     * 평소의 동기화를 계속한다(다음 동기화에서 다시 시도). 평소의 받기 · reset 의 실패는 그대로 던진다.
     */
    override fun syncToRemoteHead(): String {
        if (!File(repoDir, ".git").exists()) {
            repoDir.parentFile.mkdirs()
            run("clone", remote, repoDir.absolutePath, workDir = repoDir.parentFile)
            history = historyAfterReceiving()
        } else {
            history = deepenIfShallow()
            run("fetch", "origin", "main")
            run("reset", "--hard", "origin/main")
        }
        return run("rev-parse", "HEAD").trim()
    }

    override fun historyState(): HistoryState = history

    /** 얕은지 묻고, 얕으면 깊게 받는다. 실패 · 시간 초과 · 참도 거짓도 아닌 답은 던지지 않고 그 문구를 problem 으로 돌려준다 */
    private fun deepenIfShallow(): HistoryState {
        val answer = try {
            run("rev-parse", "--is-shallow-repository").trim()
        } catch (error: IllegalStateException) {
            return HistoryState(shallow = null, problem = error.message ?: error.toString())
        }
        return when (answer) {
            "false" -> HistoryState(shallow = false, problem = null)
            "true" -> try {
                run("fetch", "--unshallow", "origin", "main", timeout = deepenTimeout)
                historyAfterReceiving()
            } catch (error: IllegalStateException) {
                HistoryState(shallow = true, problem = withLeftLock(error.message ?: error.toString()))
            }
            else -> HistoryState(shallow = null, problem = "얕은지 알 수 없는 답(rev-parse --is-shallow-repository): ${answer.take(300)}")
        }
    }

    /**
     * 깊게 받기가 실패한 뒤 `.git/shallow.lock` 이 남아 있으면 그것을 글의 앞머리에 둔다(WARN 은 앞 300자만 싣는다).
     * 잠금은 지우지 않는다 — 같은 볼륨을 쓰는 다른 자리의 git 이 쥔 잠금일 수 있다
     */
    private fun withLeftLock(gitProblem: String): String {
        val lockFile = File(repoDir, ".git/shallow.lock")
        if (!lockFile.exists()) return gitProblem
        return "잠금 파일이 남아 있다(${lockFile.absolutePath}) — 다른 git 이 돌고 있지 않은지 확인한 뒤 지운다. $gitProblem"
    }

    /**
     * 받기가 성공한 뒤의 상태 — 원격이 얕은 사본이면 받기가 성공해도 git 의 얕은 표시(`.git/shallow`)가 남는다. 그때는 얕다고 드러낸다
     * (git 을 더 띄우지 않는다). `.git` 이 폴더가 아니면(작업 공간의 `gitdir:` 파일) 표시를 볼 수 없어 받기의 성공대로 얕지 않다고 둔다
     */
    private fun historyAfterReceiving(): HistoryState =
        if (File(repoDir, ".git/shallow").exists()) HistoryState(shallow = true, problem = STILL_SHALLOW)
        else HistoryState(shallow = false, problem = null)

    /** prev..HEAD의 md 변경 목록. shallow 경계 밖 prev면 ShaUnresolvable → 호출부가 full로 강등 (B7) */
    override fun changedMarkdown(prevSha: String, headSha: String): List<Pair<Char, String>> {
        val output = try {
            // core.quotepath=off — 한글 경로 8진수 이스케이프 차단 (실측)
            run("-c", "core.quotepath=off", "diff", "--name-status", "-M",
                "$prevSha..$headSha", "--", "*.md")
        } catch (error: IllegalStateException) {
            if (error.message?.contains("bad object") == true ||
                error.message?.contains("unknown revision") == true)
                throw ShaUnresolvableException("prev=$prevSha (shallow 경계 밖 추정)")
            else throw error
        }
        // denylist(docs·templates)는 색인·트리 입력에서 제외 (PathGuard.HIDDEN_TOP_LEVEL)
        return parseNameStatus(output).filter { !PathGuard.isHidden(it.second) }
    }

    override fun allMarkdown(): List<String> =
        run("-c", "core.quotepath=off", "ls-files", "*.md").lines()
            .filter { it.isNotBlank() && !PathGuard.isHidden(it) }

    override fun readFile(path: String): String = File(repoDir, path).readText()

    /** 요청 중의 읽기 — [requestTimeout] 안에 끝나지 않으면 장애(IllegalStateException) */
    override fun currentHead(): String = run("rev-parse", "HEAD", timeout = requestTimeout, grace = Duration.ZERO).trim()

    override fun recentCommits(limit: Int): List<CommitInfo> =
        run("log", "--pretty=%H%x09%cI%x09%s", "-n", limit.toString())
            .lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split("\t", limit = 3)
                CommitInfo(sha = parts[0], at = parts.getOrElse(1) { "" }, message = parts.getOrElse(2) { "" })
            }

    /**
     * 그 판의 그 파일(요청 중의 읽기 — [requestTimeout]). git 이 "그 판에 그 파일이 없다 · 그 판이 없다"고 답한 경우만
     * FileNotFoundException(없음 — 문서 주소는 404), 그 밖의 실패(시간 초과 · 저장소가 아님 · 깨진 객체)는 IllegalStateException(장애)
     */
    override fun readFileAt(sha: String, path: String): String {
        val (exitCode, output) = execute(arrayOf("-c", "core.quotepath=off", "show", "$sha:$path"), repoDir, requestTimeout, Duration.ZERO)
        if (exitCode == 0) return output
        if (NOT_FOUND_ANSWERS.any { answer -> answer.containsMatchIn(output) }) throw java.io.FileNotFoundException("$sha:$path")
        error("git 실패(show $sha:$path): ${output.take(300)}")
    }

    private fun run(vararg arguments: String, workDir: File = repoDir, timeout: Duration = syncTimeout, grace: Duration = terminationGrace): String {
        val (exitCode, output) = execute(arguments, workDir, timeout, grace)
        check(exitCode == 0) { "git 실패(${arguments.joinToString(" ")}): ${output.take(300)}" }
        return output
    }

    /**
     * git 을 돌려 (종료 코드, 표준 출력 + 표준 오류)를 돌려준다. [timeout] 을 넘으면 그 프로세스와 그 아래 프로세스를 [stop] 으로 끝내고
     * IllegalStateException. [grace] 가 0 이면(요청 중의 읽기) 기다리지 않고 곧바로 강제로 끊는다
     */
    private fun execute(arguments: Array<out String>, workDir: File, timeout: Duration, grace: Duration): Pair<Int, String> {
        val builder = ProcessBuilder(gitCommand, *arguments).directory(workDir).redirectErrorStream(true)
        // git 의 답을 영어로 고정한다 — "없음"을 그 문구로 가른다(로케일이 한국어면 문구가 바뀐다)
        builder.environment()["LC_ALL"] = "C"
        val process = builder.start()
        // 출력 소비를 별도 스레드로 — 프로세스 행 시 readText가 waitFor 이전에 무한 블록하는 것 방지 (B8)
        val outputBuffer = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { outputBuffer.appendLine(it) } }
        reader.start()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // 아래 프로세스가 출력을 쥐고 있으면 읽기 스레드가 풀리지 않는다 — 함께 끝낸다
            stop(process, grace)
            reader.join(2000)
            error("git timeout(${timeout.toMillis()}ms): ${arguments.joinToString(" ")}")
        }
        reader.join(5000)
        return process.exitValue() to outputBuffer.toString()
    }

    /**
     * 시간 초과한 git 과 그 아래 프로세스를 끝낸다 — 먼저 종료 신호(SIGTERM)를 보내 git 이 잠금(`.git/shallow.lock` 등)을 스스로 지우게 하고,
     * [grace] 안에 끝나지 않은 것만 강제로 끊는다. 아래 프로세스의 목록은 신호 전에 잡는다(git 이 먼저 끝나면 목록에서 사라진다)
     */
    private fun stop(process: Process, grace: Duration) {
        val processes = process.descendants().toList() + process.toHandle()
        if (!grace.isZero) {
            processes.forEach { handle -> handle.destroy() }
            val deadline = System.nanoTime() + grace.toNanos()
            while (processes.any { handle -> isRunning(handle) } && System.nanoTime() < deadline) Thread.sleep(50)
        }
        processes.filter { handle -> isRunning(handle) }.forEach { handle -> handle.destroyForcibly() }
    }

    /**
     * 아직 도는가 — 거둬지지 않은 좀비(`/proc/<pid>/stat` 의 상태 Z)는 끝난 것으로 본다. 컨테이너의 PID 1 이 java 면
     * git 이 끝난 뒤 고아가 된 아래 프로세스가 거둬지지 않아 [ProcessHandle.isAlive] 가 참으로 남는다. `/proc` 이 없으면 isAlive 그대로
     */
    private fun isRunning(handle: ProcessHandle): Boolean {
        if (!handle.isAlive) return false
        val stat = try { File("/proc/${handle.pid()}/stat").readText() } catch (_: Exception) { return handle.isAlive }
        return stat.substringAfterLast(")").trim().firstOrNull() != 'Z'
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(GitRepository::class.java)

        /** 요청 중의 git 읽기의 기본 시간 제한(명세 23) */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        /** 내용 동기화 · 목록 명령의 기본 시간 제한(명령마다 — 첫 깊게 받기도 이 안에 든다) */
        private val SYNC_TIMEOUT: Duration = Duration.ofSeconds(120)
        /** 깊게 받기의 기본 시간 제한 */
        val DEEPEN_TIMEOUT: Duration = Duration.ofSeconds(300)
        /** 종료 신호 뒤 강제로 끊기 전까지의 기본 기다림 */
        val TERMINATION_GRACE: Duration = Duration.ofSeconds(5)
        /** 깊게 받기의 시간 제한(초)을 바꾸는 환경 변수 */
        private const val DEEPEN_TIMEOUT_VARIABLE = "STUDY_NOTE_DEEPEN_TIMEOUT_SECONDS"

        /** [DEEPEN_TIMEOUT_VARIABLE] 의 값 — 없으면 기본값, 양의 정수가 아니면 기본값을 쓰고 WARN 한 줄(뜨는 것을 막지 않는다) */
        private fun deepenTimeoutFromEnvironment(): Duration {
            val value = System.getenv(DEEPEN_TIMEOUT_VARIABLE) ?: return DEEPEN_TIMEOUT
            val seconds = value.trim().toLongOrNull()
            if (seconds == null || seconds <= 0) {
                logger.warn("$DEEPEN_TIMEOUT_VARIABLE='$value' 은 양의 정수가 아니다 — 기본 ${DEEPEN_TIMEOUT.seconds}초를 쓴다")
                return DEEPEN_TIMEOUT
            }
            return Duration.ofSeconds(seconds)
        }
        /** 받기가 성공했는데 얕은 표시가 남은 때의 problem — 다음 동기화에서 다시 깊게 받기를 시도한다 */
        private const val STILL_SHALLOW = "받기는 끝났으나 저장소가 아직 얕다(.git/shallow 가 남음 — 원격이 얕은 사본일 수 있다). 다음 동기화에서 다시 깊게 받는다"

        /** `git show <판>:<경로>` 의 "없음" 답(C 로케일) — 그 판에 그 파일이 없음 · 작업 트리에만 있음 · 그 판이 없음 */
        private val NOT_FOUND_ANSWERS = listOf(
            Regex("""fatal: path '.*' does not exist in '"""),
            Regex("""fatal: path '.*' exists on disk, but not in '"""),
            Regex("""fatal: invalid object name '"""),
        )

        /** --name-status 출력 파싱 (순수 함수 — 테스트 이음새, B9). R은 D(구)+A(신)로 분해 */
        fun parseNameStatus(output: String): List<Pair<Char, String>> =
            output.lines().filter { it.isNotBlank() }.flatMap { line ->
                val parts = line.split("\t")
                when {
                    parts[0].startsWith("R") && parts.size >= 3 -> listOf('D' to parts[1], 'A' to parts[2])
                    parts.size >= 2 -> listOf(parts[0][0] to parts[1])
                    else -> emptyList()
                }
            }
    }
}
