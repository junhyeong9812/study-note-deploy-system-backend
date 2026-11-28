package xyz.junproject.backend.shared.infra

import xyz.junproject.backend.indexing.usecase.DocumentReader

import xyz.junproject.backend.content.domain.PathGuard
import xyz.junproject.backend.content.usecase.CommitInfo
import xyz.junproject.backend.content.usecase.NoteSourcePort

import xyz.junproject.backend.sync.usecase.ShaUnresolvableException

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
    /** 요청 중의 읽기(readFileAt · currentHead)의 시간 제한(명세 23 — 기본 5초). 내용 동기화 · 목록 명령은 [SYNC_TIMEOUT] */
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
) : SourceControlPort, NoteSourcePort, DocumentReader {
    @Autowired
    constructor() : this(File(System.getenv("REPO_DIR") ?: "/data/study-note/repo"))

    private val remote = System.getenv("STUDY_NOTE_REMOTE")
        ?: "https://github.com/junhyeong9812/study-note.git"

    override fun syncToRemoteHead(): String {
        if (!File(repoDir, ".git").exists()) {
            repoDir.parentFile.mkdirs()
            run("clone", "--depth=50", remote, repoDir.absolutePath, workDir = repoDir.parentFile)
        } else {
            run("fetch", "--depth=50", "origin", "main")
            run("reset", "--hard", "origin/main")
        }
        return run("rev-parse", "HEAD").trim()
    }

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
    override fun currentHead(): String = run("rev-parse", "HEAD", timeout = requestTimeout).trim()

    override fun recentCommits(limit: Int): List<CommitInfo> =
        run("log", "--pretty=%H%x09%cI%x09%s", "-n", limit.toString())
            .lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split("\t", limit = 3)
                CommitInfo(sha = parts[0], at = parts.getOrElse(1) { "" }, message = parts.getOrElse(2) { "" })
            }

    /**
     * 그 판의 그 파일(요청 중의 읽기 — [requestTimeout]). git 이 "그 판에 그 파일이 없다 · 그 판이 없다"고 답한 경우만
     * FileNotFoundException(없음 — 문서 주소는 404), 그 밖의 실패(시간 초과 · 저장소가 아님 · 깨진 객체)는 IllegalStateException(장애).
     * 판 표시는 커밋으로 묶어(`^{commit}`) 읽는다 — 폴더의 tree · 글의 blob id 를 판으로 주면 그 안의 숨김 글이 읽히기 때문이다.
     * 커밋이 아닌 객체는 git 이 "invalid object name" 으로 답해 없음이 되고, 주석 달린 태그는 가리키는 커밋으로 풀린다
     */
    override fun readFileAt(sha: String, path: String): String {
        val (exitCode, output) = execute(arrayOf("-c", "core.quotepath=off", "show", "$sha^{commit}:$path"), repoDir, requestTimeout)
        if (exitCode == 0) return output
        if (NOT_FOUND_ANSWERS.any { answer -> answer.containsMatchIn(output) }) throw java.io.FileNotFoundException("$sha:$path")
        error("git 실패(show $sha:$path): ${output.take(300)}")
    }

    private fun run(vararg arguments: String, workDir: File = repoDir, timeout: Duration = SYNC_TIMEOUT): String {
        val (exitCode, output) = execute(arguments, workDir, timeout)
        check(exitCode == 0) { "git 실패(${arguments.joinToString(" ")}): ${output.take(300)}" }
        return output
    }

    /** git 을 돌려 (종료 코드, 표준 출력 + 표준 오류)를 돌려준다. [timeout] 을 넘으면 그 프로세스와 그 아래 프로세스를 끊고 IllegalStateException */
    private fun execute(arguments: Array<out String>, workDir: File, timeout: Duration): Pair<Int, String> {
        val builder = ProcessBuilder(gitCommand, *arguments).directory(workDir).redirectErrorStream(true)
        // git 의 답을 영어로 고정한다 — "없음"을 그 문구로 가른다(로케일이 한국어면 문구가 바뀐다)
        builder.environment()["LC_ALL"] = "C"
        val process = builder.start()
        // 출력 소비를 별도 스레드로 — 프로세스 행 시 readText가 waitFor 이전에 무한 블록하는 것 방지 (B8)
        val outputBuffer = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { outputBuffer.appendLine(it) } }
        reader.start()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // 아래 프로세스가 출력을 쥐고 있으면 읽기 스레드가 풀리지 않는다 — 함께 끊는다
            process.descendants().forEach { descendant -> descendant.destroyForcibly() }
            process.destroyForcibly()
            reader.join(2000)
            error("git timeout(${timeout.toMillis()}ms): ${arguments.joinToString(" ")}")
        }
        reader.join(5000)
        return process.exitValue() to outputBuffer.toString()
    }

    companion object {
        /** 요청 중의 git 읽기의 기본 시간 제한(명세 23) */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        /** 내용 동기화 · 목록 명령의 시간 제한 */
        private val SYNC_TIMEOUT: Duration = Duration.ofSeconds(120)

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
