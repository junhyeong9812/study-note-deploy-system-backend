package xyz.junproject.backend.sync

import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/** study-note clone 볼륨 관리 — shell git (컨테이너에 git 설치, es-index.md D5-1: diff가 색인 입력의 정본). */
@Component
class GitRepository {
    private val repoDir = File(System.getenv("REPO_DIR") ?: "/data/study-note/repo")
    private val remote = System.getenv("STUDY_NOTE_REMOTE")
        ?: "https://github.com/junhyeong9812/study-note.git"

    fun syncToRemoteHead(): String {
        if (!File(repoDir, ".git").exists()) {
            repoDir.parentFile.mkdirs()
            run("git", "clone", "--depth=50", remote, repoDir.absolutePath, workDir = repoDir.parentFile)
        } else {
            run("git", "fetch", "--depth=50", "origin", "main")
            run("git", "reset", "--hard", "origin/main")
        }
        return run("git", "rev-parse", "HEAD").trim()
    }

    /** prev..HEAD의 md 변경 목록. 반환: (상태 A/M/D, 경로) — R은 D(구경로)+A(신경로)로 풀어서 반환 */
    fun changedMarkdown(prevSha: String, headSha: String): List<Pair<Char, String>> {
        val output = run("git", "diff", "--name-status", "-M", "$prevSha..$headSha", "--", "*.md")
        return output.lines().filter { it.isNotBlank() }.flatMap { line ->
            val parts = line.split("\t")
            when {
                parts[0].startsWith("R") -> listOf('D' to parts[1], 'A' to parts[2])
                else -> listOf(parts[0][0] to parts[1])
            }
        }
    }

    fun allMarkdown(): List<String> =
        run("git", "ls-files", "*.md").lines().filter { it.isNotBlank() }

    fun readFile(path: String): String = File(repoDir, path).readText()

    private fun run(vararg command: String, workDir: File = repoDir): String {
        val process = ProcessBuilder(*command).directory(workDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); error("git timeout: ${command.joinToString(" ")}") }
        check(process.exitValue() == 0) { "git 실패(${command.joinToString(" ")}): ${output.take(300)}" }
        return output
    }
}
