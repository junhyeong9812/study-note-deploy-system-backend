package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 실제 git 으로 도는 동기화(명세 "H0 리뷰의 반영" 4 · 5절 H0 의 검증).
 * 임시 폴더에 원격(맨 저장소 — 커밋 서른 개, 커밋마다 같은 파일을 50KB 의 새 내용으로 바꾼다)과 `--depth` 로 받은 얕은 사본을 만들고,
 * 실제 [GitRepository] 로 동기화한다. 얕은 사본에는 옛 판의 내용이 없어 깊게 받기가 실제로 묶음을 받는다.
 * git 이 PATH 에 없으면 건너뛴다(건너뜀으로 센다). 느린 원격은 perl 로 받기의 흐름을 늦춘다 — perl 이 없으면 그 테스트만 건너뛴다.
 * 이 테스트가 띄우는 git 은 사용자 설정을 읽지 않는다(`GIT_CONFIG_GLOBAL=/dev/null`) — [GitRepository] 가 띄우는 git 은 환경을 그대로 물려받는다.
 */
class GitRepositoryRealGitTest {
    @TempDir lateinit var temp: Path

    private val commitCount = 30
    private val shallowDepth = 3

    private val workDir by lazy { File(temp.toFile(), "work") }
    private val remoteDir by lazy { File(temp.toFile(), "remote.git") }
    private val repoDir by lazy { File(temp.toFile(), "repo") }
    private val lockFile by lazy { File(repoDir, ".git/shallow.lock") }

    @BeforeEach
    fun requireGit() {
        assumeTrue(commandWorks("git", "--version"), "git 이 PATH 에 없다 — 실제 git 테스트를 건너뛴다")
    }

    private fun commandWorks(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    /** 이 테스트가 준비 · 확인에 쓰는 git — 실패하면 그 출력을 실어 멈춘다 */
    private fun realGit(directory: File, vararg arguments: String): String {
        val builder = ProcessBuilder("git", *arguments).directory(directory).redirectErrorStream(true)
        builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        builder.environment()["LC_ALL"] = "C"
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "준비의 git 이 끝나지 않았다: ${arguments.joinToString(" ")}")
        assertEquals(0, process.exitValue(), "준비의 git 이 실패했다(${arguments.joinToString(" ")}): $output")
        return output.trim()
    }

    /** 커밋 [commitCount] 개의 원격(맨 저장소)을 만든다 — 커밋마다 data.bin 을 새 내용으로 바꾼다 */
    private fun createRemote() {
        workDir.mkdirs()
        realGit(workDir, "init", "-q", "-b", "main")
        val random = Random(20261125)
        for (number in 1..commitCount) {
            File(workDir, "data.bin").writeBytes(random.nextBytes(50_000))
            File(workDir, "note.md").writeText("# note $number\n")
            realGit(workDir, "add", "-A")
            realGit(workDir, "-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "commit", "-q", "-m", "commit $number")
        }
        realGit(temp.toFile(), "clone", "-q", "--bare", workDir.absolutePath, remoteDir.absolutePath)
    }

    private fun shallowCloneOf(remote: File, depth: Int) {
        realGit(temp.toFile(), "clone", "-q", "--depth", depth.toString(), "file://${remote.absolutePath}", repoDir.absolutePath)
        assertEquals("true", realGit(repoDir, "rev-parse", "--is-shallow-repository"), "준비: 사본이 얕지 않다")
    }

    private fun repository(
        syncTimeout: Duration = Duration.ofSeconds(30),
        deepenTimeout: Duration = Duration.ofSeconds(30),
        terminationGrace: Duration = Duration.ofSeconds(3),
    ) = GitRepository(
        repoDir, "git",
        requestTimeout = Duration.ofSeconds(5),
        syncTimeout = syncTimeout,
        deepenTimeout = deepenTimeout,
        terminationGrace = terminationGrace,
    )

    private fun commitsIn(directory: File): Int = realGit(directory, "rev-list", "--count", "HEAD").toInt()

    @Test
    fun `얕은 사본은 동기화 한 번 뒤 원격과 같은 수의 커밋을 갖고 얕지 않다`() {
        createRemote()
        shallowCloneOf(remoteDir, shallowDepth)
        assertEquals(shallowDepth, commitsIn(repoDir), "준비: 얕은 사본의 커밋 수")

        val gitRepository = repository()
        val head = gitRepository.syncToRemoteHead()

        assertEquals(realGit(remoteDir, "rev-parse", "main"), head)
        assertEquals(commitCount, commitsIn(repoDir))
        assertEquals(commitsIn(remoteDir), commitsIn(repoDir))
        assertEquals("false", realGit(repoDir, "rev-parse", "--is-shallow-repository"))
        assertEquals(false, gitRepository.historyState().shallow, gitRepository.historyState().toString())
        assertNull(gitRepository.historyState().problem)
        assertFalse(File(repoDir, ".git/shallow").exists())
    }

    @Test
    fun `얕은 원격에서 받은 사본은 동기화가 성공해도 아직 얕다`() {
        createRemote()
        val shallowRemote = File(temp.toFile(), "shallow-remote.git")
        realGit(temp.toFile(), "clone", "-q", "--bare", "--depth", "10", "file://${remoteDir.absolutePath}", shallowRemote.absolutePath)
        shallowCloneOf(shallowRemote, shallowDepth)

        val gitRepository = repository()
        assertEquals(realGit(remoteDir, "rev-parse", "main"), gitRepository.syncToRemoteHead())

        assertEquals(10, commitsIn(repoDir), "얕은 원격이 가진 만큼은 받는다")
        val state = gitRepository.historyState()
        assertEquals(true, state.shallow, state.toString())
        assertTrue(state.problem?.contains("아직 얕다") == true, "아직 얕다는 글이 없다: ${state.problem}")
    }

    @Test
    fun `깊게 받기가 시간 초과로 끊겨도 잠금이 남지 않아 다음 동기화가 다시 깊게 받는다`() {
        assumeTrue(commandWorks("perl", "-e", "1"), "perl 이 없다 — 느린 원격을 만들 수 없다")
        createRemote()
        shallowCloneOf(remoteDir, shallowDepth)
        // 원격의 내보내기를 64KB 마다 0.2초씩 늦춘다 — 깊게 받기의 묶음(약 1.5MB)이 몇 초 걸려, 받는 중(잠금을 쥔 채)에 상한을 넘는다
        val slowUploadPack = File(temp.toFile(), "slow-upload-pack")
        slowUploadPack.writeText(
            "#!/bin/sh\ngit-upload-pack \"\$@\" | perl -e 'while (sysread(STDIN, \$chunk, 65536)) " +
                "{ syswrite(STDOUT, \$chunk); select(undef, undef, undef, 0.2); }'\n"
        )
        slowUploadPack.setExecutable(true)
        realGit(repoDir, "config", "remote.origin.uploadpack", slowUploadPack.absolutePath)

        val gitRepository = repository(syncTimeout = Duration.ofSeconds(1), deepenTimeout = Duration.ofSeconds(1), terminationGrace = Duration.ofSeconds(3))
        val firstHead = gitRepository.syncToRemoteHead()
        assertEquals(realGit(remoteDir, "rev-parse", "main"), firstHead, "깊게 받기가 끊겨도 평소의 동기화는 끝까지 간다")
        val afterTimeout = gitRepository.historyState()
        assertEquals(true, afterTimeout.shallow, afterTimeout.toString())
        assertTrue(afterTimeout.problem?.contains("timeout") == true, "준비: 첫 동기화의 깊게 받기가 시간 초과가 아니다: $afterTimeout")
        assertFalse(lockFile.exists(), "시간 초과로 끊긴 깊게 받기가 .git/shallow.lock 을 남겼다")

        realGit(repoDir, "config", "--unset", "remote.origin.uploadpack")
        val nextRepository = repository()
        nextRepository.syncToRemoteHead()

        assertEquals(false, nextRepository.historyState().shallow, "다음 동기화가 깊게 받지 못했다: ${nextRepository.historyState()}")
        assertNull(nextRepository.historyState().problem)
        assertEquals(commitCount, commitsIn(repoDir))
        assertEquals("false", realGit(repoDir, "rev-parse", "--is-shallow-repository"))
    }
}
