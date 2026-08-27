package xyz.junproject.backend.infra

import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/** study-note clone 볼륨 관리 — shell git (es-index.md D5-1: diff가 색인 입력의 정본). */
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

    class ShaUnresolvable(message: String) : RuntimeException(message)

    /** prev..HEAD의 md 변경 목록. shallow 경계 밖 prev면 ShaUnresolvable → 호출부가 full로 강등 (B7) */
    fun changedMarkdown(prevSha: String, headSha: String): List<Pair<Char, String>> {
        val output = try {
            // core.quotepath=off — 한글 경로 8진수 이스케이프 차단 (실측)
            run("git", "-c", "core.quotepath=off", "diff", "--name-status", "-M",
                "$prevSha..$headSha", "--", "*.md")
        } catch (error: IllegalStateException) {
            if (error.message?.contains("bad object") == true ||
                error.message?.contains("unknown revision") == true)
                throw ShaUnresolvable("prev=$prevSha (shallow 경계 밖 추정)")
            else throw error
        }
        return parseNameStatus(output)
    }

    fun allMarkdown(): List<String> =
        run("git", "-c", "core.quotepath=off", "ls-files", "*.md").lines().filter { it.isNotBlank() }

    fun readFile(path: String): String = File(repoDir, path).readText()

    private fun run(vararg command: String, workDir: File = repoDir): String {
        val process = ProcessBuilder(*command).directory(workDir).redirectErrorStream(true).start()
        // 출력 소비를 별도 스레드로 — 프로세스 행 시 readText가 waitFor 이전에 무한 블록하는 것 방지 (B8)
        val outputBuffer = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { outputBuffer.appendLine(it) } }
        reader.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2000)
            error("git timeout: ${command.joinToString(" ")}")
        }
        reader.join(5000)
        check(process.exitValue() == 0) { "git 실패(${command.joinToString(" ")}): ${outputBuffer.take(300)}" }
        return outputBuffer.toString()
    }

    companion object {
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
