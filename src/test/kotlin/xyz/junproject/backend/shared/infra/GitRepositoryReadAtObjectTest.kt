package xyz.junproject.backend.shared.infra

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.junproject.backend.content.api.ContentController
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 옛 판 읽기의 판 표시(`at`)는 커밋만 가리킨다 — 커밋이 아닌 객체(폴더의 tree · 글의 blob · 루트 tree)는 없음(FileNotFoundException → `/api/doc` 은 404).
 * `git show <tree>:<경로>` 는 그 폴더 안에서 경로를 찾는다 — `docs` 폴더의 tree id 와 경로 `secret.md` 를 주면 경로는 숨김 판정에 걸리지 않는데
 * 숨김 글이 읽힌다. 주석 달린 태그는 그것이 가리키는 커밋으로 풀어 받는다(그때도 경로의 숨김 판정은 그대로다).
 * 커밋 id(전체 · 접두)로는 지금처럼 읽는다. 없는 id · 모호한 접두는 없음이다.
 * 실제 git 저장소(임시 폴더)와 실제 [GitRepository] 로 읽는다. git 이 PATH 에 없으면 건너뛴다.
 */
class GitRepositoryReadAtObjectTest {
    @TempDir lateinit var temp: Path

    private val repoDir by lazy { File(temp.toFile(), "repo") }
    private val secrets = listOf("SECRET-PLAN-1a", "SECRET-DEEP-2b", "SECRET-TEMPLATE-3c", "SECRET-QUIZ-4d")

    private lateinit var firstCommit: String
    private lateinit var secondCommit: String
    private lateinit var tagObject: String
    private lateinit var docsTree: String
    private lateinit var templatesTree: String
    private lateinit var plansTree: String
    private lateinit var secretBlob: String
    private lateinit var rootTree: String

    private fun realGit(vararg arguments: String): Pair<Int, String> {
        val builder = ProcessBuilder("git", *arguments).directory(repoDir).redirectErrorStream(true)
        builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        builder.environment()["LC_ALL"] = "C"
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "git 이 끝나지 않았다: ${arguments.joinToString(" ")}")
        return process.exitValue() to output
    }

    private fun prepare(vararg arguments: String): String {
        val (exitCode, output) = realGit(*arguments)
        assertEquals(0, exitCode, "준비의 git 이 실패했다(${arguments.joinToString(" ")}): $output")
        return output.trim()
    }

    private fun write(path: String, text: String) {
        val file = File(repoDir, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun commit(message: String): String {
        prepare("add", "-A")
        prepare("-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "commit", "-q", "-m", message)
        return prepare("rev-parse", "HEAD")
    }

    @BeforeEach
    fun createRepository() {
        val gitWorks = try { ProcessBuilder("git", "--version").start().waitFor() == 0 } catch (_: Exception) { false }
        assumeTrue(gitWorks, "git 이 PATH 에 없다")
        repoDir.mkdirs()
        prepare("init", "-q", "-b", "main")
        write("cs/a.md", "# public first\n")
        write("docs/secret.md", "# plan\n${secrets[0]}\n")
        write("docs/plans/x.md", "# deep\n${secrets[1]}\n")
        write("templates/t.md", "# template\n${secrets[2]}\n")
        // 공개 글과 같은 꼴(`cs/<주제>/1-question.md`)을 숨김 폴더 안에 — 폴더의 tree 를 판으로 주면 공개 경로처럼 보인다
        write("docs/cs/os/1-question.md", "# question\n${secrets[3]}\n")
        firstCommit = commit("first")
        prepare("-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "tag", "-a", "v1", "-m", "release one", firstCommit)
        write("cs/a.md", "# public second\n")
        secondCommit = commit("second")

        tagObject = prepare("rev-parse", "v1")
        docsTree = prepare("rev-parse", "HEAD:docs")
        templatesTree = prepare("rev-parse", "HEAD:templates")
        plansTree = prepare("rev-parse", "HEAD:docs/plans")
        secretBlob = prepare("rev-parse", "HEAD:docs/secret.md")
        rootTree = prepare("rev-parse", "HEAD^{tree}")
        assertEquals("tag", prepare("cat-file", "-t", tagObject), "준비: 주석 달린 태그가 아니다")
    }

    /** [GitRepository] 는 저장소 폴더를 환경 변수(REPO_DIR)로만 받는다 — 만든 뒤 그 칸을 임시 저장소로 바꾼다(SyncServiceTest 의 stateFile 과 같은 꼴) */
    private fun repository(): GitRepository = GitRepository().also { git ->
        val field = GitRepository::class.java.getDeclaredField("repoDir")
        field.isAccessible = true
        field.set(git, repoDir)
    }

    private fun assertNotFound(at: String, path: String) {
        val outcome = try { repository().readFileAt(at, path) } catch (error: Exception) { error }
        assertTrue(outcome is FileNotFoundException, "커밋이 아닌 판 표시($at : $path)가 없음이 아니다: ${outcome.toString().take(200)}")
        secrets.forEach { secret -> assertFalse(outcome.toString().contains(secret), "숨김 글의 내용이 나왔다($at : $path)") }
    }

    @Test
    fun `준비 - 판 표시를 커밋으로 묶지 않으면 폴더의 tree id 로 숨김 글이 실제로 읽힌다`() {
        val (exitCode, output) = realGit("show", "$docsTree:secret.md")
        assertEquals(0, exitCode, output)
        assertTrue(output.contains(secrets[0]), output)
    }

    @Test
    fun `숨김 폴더의 tree id 는 전체든 접두든 판이 아니어서 없음이다`() {
        assertNotFound(docsTree, "secret.md")
        assertNotFound(docsTree.take(7), "secret.md")
        assertNotFound(templatesTree, "t.md")
        assertNotFound(plansTree, "x.md")
        assertNotFound(docsTree, "plans/x.md")
        assertNotFound(docsTree, "cs/os/1-question.md")
    }

    @Test
    fun `글의 blob id 와 루트 tree id 도 판이 아니어서 없음이다`() {
        assertNotFound(secretBlob, "secret.md")
        assertNotFound(secretBlob, "cs/a.md")
        assertNotFound(secretBlob.take(7), "x.md")
        assertNotFound(rootTree, "cs/a.md")
        assertNotFound(rootTree, "docs/secret.md")
    }

    @Test
    fun `커밋 id 는 전체든 접두든 지금처럼 그 판의 글을 읽는다`() {
        assertEquals("# public first\n", repository().readFileAt(firstCommit, "cs/a.md"))
        assertEquals("# public first\n", repository().readFileAt(firstCommit.take(7), "cs/a.md"))
        assertEquals("# public second\n", repository().readFileAt(secondCommit, "cs/a.md"))
        assertEquals("# public second\n", repository().readFileAt(secondCommit.take(10), "cs/a.md"))
    }

    @Test
    fun `주석 달린 태그는 그것이 가리키는 커밋으로 풀어 읽는다`() {
        assertEquals("# public first\n", repository().readFileAt(tagObject, "cs/a.md"))
        assertEquals("# public first\n", repository().readFileAt(tagObject.take(8), "cs/a.md"))
    }

    @Test
    fun `없는 id 와 모호한 접두는 없음이다`() {
        assertNotFound("0000000", "cs/a.md")
        assertNotFound("0".repeat(40), "cs/a.md")
        // 같은 4글자로 시작하는 blob 둘을 만든다 — 그 접두는 어느 객체인지 정할 수 없다
        val blobs = File(temp.toFile(), "blobs").also { it.mkdirs() }
        val files = (1..800).map { number -> File(blobs, "$number.txt").also { it.writeText("blob $number\n") } }
        val listFile = File(temp.toFile(), "blob-paths.txt").also { file -> file.writeText(files.joinToString("\n") { it.absolutePath } + "\n") }
        val hashed = run {
            val builder = ProcessBuilder("git", "hash-object", "-w", "--stdin-paths").directory(repoDir).redirectErrorStream(true)
            builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            builder.redirectInput(listFile)
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            output.lines().filter { it.matches(Regex("^[0-9a-f]{40}$")) }
        }
        val known = (hashed + listOf(firstCommit, secondCommit, tagObject, docsTree, templatesTree, plansTree, secretBlob, rootTree))
        val ambiguous = hashed.groupBy { it.take(4) }.entries.firstOrNull { (prefix, group) ->
            group.size >= 2 && known.none { other -> other.startsWith(prefix) && other !in group }
        }?.key
        assertNotNull(ambiguous, "준비: 같은 4글자로 시작하는 blob 둘을 만들지 못했다")
        assertNotFound(ambiguous!!, "cs/a.md")
    }

    // ---- `/api/doc` 까지 ----

    private val requestLog = mockk<RequestLog>(relaxed = true)
    private val mockMvc by lazy {
        MockMvcBuilders.standaloneSetup(ContentController(repository(), mockk(relaxed = true), requestLog))
            .setControllerAdvice(GlobalErrorHandler(requestLog)).build()
    }

    private fun requestDoc(path: String, at: String): MockHttpServletResponse =
        mockMvc.perform(get("/api/doc").param("path", path).param("at", at)).andReturn().response

    private fun assertHiddenWithoutSecret(response: MockHttpServletResponse, what: String) {
        assertEquals(404, response.status, "$what: ${response.contentAsString}")
        assertTrue(response.contentAsString.contains("\"not_found\""), "$what: ${response.contentAsString}")
        secrets.forEach { secret -> assertFalse(response.contentAsString.contains(secret), "$what: 숨김 글의 내용이 응답에 있다") }
    }

    @Test
    fun `문서 읽기에 폴더의 tree id 와 blob id 를 판으로 주면 404 이고 숨김 글의 내용이 없다`() {
        assertHiddenWithoutSecret(requestDoc("secret.md", docsTree), "path=secret.md at=docs tree")
        assertHiddenWithoutSecret(requestDoc("secret.md", docsTree.take(7)), "path=secret.md at=docs tree 의 7글자")
        assertHiddenWithoutSecret(requestDoc("x.md", plansTree), "path=x.md at=docs/plans tree")
        assertHiddenWithoutSecret(requestDoc("t.md", templatesTree), "path=t.md at=templates tree")
        assertHiddenWithoutSecret(requestDoc("cs/os/1-question.md", docsTree), "path=cs/os/1-question.md at=docs tree")
        assertHiddenWithoutSecret(requestDoc("secret.md", secretBlob), "path=secret.md at=blob")
        assertHiddenWithoutSecret(requestDoc("cs/a.md", rootTree), "path=cs/a.md at=root tree")
    }

    @Test
    fun `문서 읽기에 태그를 판으로 주면 그 커밋의 공개 글은 읽히고 숨김 글은 여전히 404 다`() {
        val publicRead = requestDoc("cs/a.md", tagObject)
        assertEquals(200, publicRead.status, publicRead.contentAsString)
        assertTrue(publicRead.contentAsString.contains("# public first"), publicRead.contentAsString)
        assertHiddenWithoutSecret(requestDoc("docs/secret.md", tagObject), "path=docs/secret.md at=tag")
    }

    @Test
    fun `문서 읽기에 커밋 id 를 판으로 주면 지금처럼 그 판의 글을 읽는다`() {
        val response = requestDoc("cs/a.md", firstCommit.take(7))
        assertEquals(200, response.status, response.contentAsString)
        assertTrue(response.contentAsString.contains("# public first"), response.contentAsString)
    }
}
