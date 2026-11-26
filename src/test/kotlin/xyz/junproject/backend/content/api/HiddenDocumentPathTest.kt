package xyz.junproject.backend.content.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.api.ChatController
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import xyz.junproject.backend.shared.infra.RequestLog
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 숨김 폴더(`docs` · `templates`)의 글은 어느 경로로도 읽히지 않는다(명세 2절) — 마디 `.` 과 빈 마디로 숨김 판정을 비껴가는 꼴까지.
 * `./docs/secret.md` · `.//docs/secret.md` 는 첫 마디가 `.` · 빈 마디라 숨김 판정을 지나지만, 파일 읽기와 `git show <판>:./…` 는 그것을 정규화해
 * 숨김 글을 읽는다. 형식이 틀린 경로로 보고 422 `invalid_request` 로 거절한다(`..` 와 같은 갈래 — 기존 관례).
 *
 * 내용 저장소는 임시 폴더의 실제 파일과 실제 git 저장소다. 노트 소스는 [NoteSourcePort] 의 두 읽기만 실제로 잇는다 —
 * 지금 판 읽기는 `File(저장소, 경로).readText()`, 옛 판 읽기는 저장소 폴더에서 `git -c core.quotepath=off show <판>:<경로>`
 * (backend 의 git 읽기와 같은 꼴 — 가짜 저장소처럼 경로를 글자 그대로 찾으면 이 결함이 보이지 않는다).
 * 채팅은 문서 지정이 거절되면 채팅 서비스(문서를 읽어 모델에 싣는 곳)를 부르지 않는다.
 * git 이 PATH 에 없으면 건너뛴다.
 */
class HiddenDocumentPathTest {
    @TempDir lateinit var temp: Path

    private val secretText = "TOP-SECRET-PLAN-7f3a"
    private val repoDir by lazy { File(temp.toFile(), "repo") }
    private lateinit var headSha: String

    private val requestLog = mockk<RequestLog>(relaxed = true)
    private val source = mockk<NoteSourcePort>()
    private val chatService = mockk<ChatService>()

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

    @BeforeEach
    fun createRepository() {
        val gitWorks = try { ProcessBuilder("git", "--version").start().waitFor() == 0 } catch (_: Exception) { false }
        assumeTrue(gitWorks, "git 이 PATH 에 없다")
        repoDir.mkdirs()
        prepare("init", "-q", "-b", "main")
        File(repoDir, "docs").mkdirs()
        File(repoDir, "docs/secret.md").writeText("# plan\n$secretText\n")
        File(repoDir, "templates").mkdirs()
        File(repoDir, "templates/secret.md").writeText("# template\n$secretText\n")
        File(repoDir, "cs").mkdirs()
        File(repoDir, "cs/a.md").writeText("# public\n")
        prepare("add", "-A")
        prepare("-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "commit", "-q", "-m", "notes")
        headSha = prepare("rev-parse", "HEAD")

        every { source.readFile(any()) } answers { File(repoDir, firstArg<String>()).readText() }
        every { source.readFileAt(any(), any()) } answers {
            val (exitCode, output) = realGit("-c", "core.quotepath=off", "show", "${firstArg<String>()}:${secondArg<String>()}")
            if (exitCode != 0) throw FileNotFoundException("${firstArg<String>()}:${secondArg<String>()}")
            output
        }
    }

    private val documentMvc by lazy {
        MockMvcBuilders.standaloneSetup(ContentController(source, mockk(relaxed = true), requestLog))
            .setControllerAdvice(GlobalErrorHandler(requestLog)).build()
    }
    private val chatMvc by lazy {
        MockMvcBuilders.standaloneSetup(ChatController(chatService, requestLog, ObjectMapper())).build()
    }

    /** 숨김 판정을 비껴가는 꼴 — 처음의 `.` · 빈 마디 · 가운데의 `.` · 상위 폴더(기존) */
    private val sneakyPaths = listOf(
        "./docs/secret.md", ".//docs/secret.md", "./templates/secret.md", "cs/../docs/secret.md",
        "cs/./../docs/secret.md", "./cs/../docs/secret.md", "docs/./secret.md", "cs//../docs/secret.md",
    )

    private fun encoded(path: String): String = URLEncoder.encode(path, Charsets.UTF_8).replace(".", "%2E")

    private fun assertRejectedWithoutSecret(response: MockHttpServletResponse, what: String) {
        assertEquals(422, response.status, "$what: ${response.contentAsString}")
        assertTrue(response.contentAsString.contains("\"invalid_request\""), "$what: ${response.contentAsString}")
        assertFalse(response.contentAsString.contains(secretText), "$what: 숨김 글의 내용이 응답에 있다")
    }

    @Test
    fun `준비 - 거절하지 않으면 이 저장소의 읽기는 그 꼴로 숨김 글을 실제로 읽는다`() {
        // 이 테스트가 공허하지 않음을 본다 — 정규화하는 읽기에 숨김 글이 실제로 닿는다
        assertTrue(source.readFile("./docs/secret.md").contains(secretText))
        assertTrue(source.readFile(".//docs/secret.md").contains(secretText))
        assertTrue(source.readFileAt(headSha, "./docs/secret.md").contains(secretText))
    }

    @Test
    fun `문서 읽기는 마디 점과 빈 마디로 숨김 글을 가리키는 경로를 422 로 거절하고 내용을 내지 않는다`() {
        sneakyPaths.forEach { path ->
            val response = documentMvc.perform(get(URI.create("/api/doc?path=${encoded(path)}"))).andReturn().response
            assertRejectedWithoutSecret(response, "/api/doc path=$path")
        }
    }

    @Test
    fun `옛 판 읽기도 마디 점과 빈 마디로 숨김 글을 가리키는 경로를 422 로 거절하고 내용을 내지 않는다`() {
        sneakyPaths.forEach { path ->
            val response = documentMvc.perform(get(URI.create("/api/doc?path=${encoded(path)}&at=$headSha"))).andReturn().response
            assertRejectedWithoutSecret(response, "/api/doc path=$path at=$headSha")
        }
    }

    @Test
    fun `주소에서 풀린 마디 점도 같다`() {
        // 스프링이 한 번 푼 뒤의 값이 `./docs/secret.md` 다
        val response = documentMvc.perform(get(URI.create("/api/doc?path=%2E%2Fdocs%2Fsecret%2Emd"))).andReturn().response
        assertRejectedWithoutSecret(response, "/api/doc path=%2E%2Fdocs%2Fsecret%2Emd")
        val atResponse = documentMvc.perform(get(URI.create("/api/doc?path=%2E%2F%2Fdocs%2Fsecret%2Emd&at=$headSha"))).andReturn().response
        assertRejectedWithoutSecret(atResponse, "/api/doc path=%2E%2F%2Fdocs%2Fsecret%2Emd at=$headSha")
    }

    @Test
    fun `공개 글은 지금 판과 옛 판 모두 그대로 읽힌다`() {
        val now = documentMvc.perform(get("/api/doc").param("path", "cs/a.md")).andReturn().response
        assertEquals(200, now.status, now.contentAsString)
        assertTrue(now.contentAsString.contains("# public"), now.contentAsString)
        val old = documentMvc.perform(get("/api/doc").param("path", "cs/a.md").param("at", headSha)).andReturn().response
        assertEquals(200, old.status, old.contentAsString)
        assertTrue(old.contentAsString.contains("# public"), old.contentAsString)
    }

    @Test
    fun `채팅의 문서 지정도 마디 점과 빈 마디로 숨김 글을 가리키면 422 이고 채팅 서비스를 부르지 않는다`() {
        sneakyPaths.forEach { path ->
            val body = ObjectMapper().writeValueAsString(mapOf("doc_path" to path, "question" to "what is the plan?"))
            val chat = chatMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
            assertRejectedWithoutSecret(chat, "/api/chat doc_path=$path")
            val escalate = chatMvc.perform(post("/api/chat/escalate").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
            assertRejectedWithoutSecret(escalate, "/api/chat/escalate doc_path=$path")
            val history = chatMvc.perform(get(URI.create("/api/chat/history?doc_path=${encoded(path)}"))).andReturn().response
            assertRejectedWithoutSecret(history, "/api/chat/history doc_path=$path")
        }
        verify(exactly = 0) { chatService.chat(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { chatService.escalateAnswer(any(), any(), any(), any()) }
        verify(exactly = 0) { chatService.history(any(), any()) }
    }
}
