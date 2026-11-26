package xyz.junproject.backend.shared.infra

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.content.api.ContentController
import xyz.junproject.backend.content.api.HistoryController
import xyz.junproject.backend.content.usecase.HistoryService
import xyz.junproject.backend.content.usecase.TreeService
import xyz.junproject.backend.indexing.usecase.IndexStore
import xyz.junproject.backend.indexing.usecase.IndexingService
import xyz.junproject.backend.indexing.usecase.TextEncoder
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import xyz.junproject.backend.sync.usecase.SyncService
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 글 저장소에 실습 폴더(주제 폴더 안의 `practice/<번호-이름>/`)가 생겨도 사이트의 글 읽기 길은 지금과 똑같다(명세 ⑭ §11 P3a) — 실제 git 저장소로 끝에서 끝까지.
 * 저장소: 주제 둘(`cs/algorithm/06-binary-search` · `cs/algorithm/07-two-pointers` — 글 셋씩) · 최상위 `practice/` 의 기존 글(숨기지 않는다) ·
 * 숨김 글 `docs/plan.md`. 판 B 에서 첫 주제 안에 실습 폴더(`problem.md` · `practice.json` · `starter/` · `tests/` · `solution/` — `solution/README.md` 포함)가 생긴다.
 * - 트리: 실습 폴더가 있어도 주제가 주제로 뜨고, `/api/tree` 의 트리 JSON 이 실습 폴더가 없을 때와 바이트까지 같다.
 * - 글 목록 · 문서 읽기(지금 판 · 옛 판) · 이력(글 하나 · 최근 변경)에 실습 폴더의 글이 없다 — 문서 · 이력은 숨김 글과 같은 응답.
 * - 동기화의 변경 목록에 실습 폴더의 파일이 오지 않고, 동기화를 거쳐도 색인에 실습 폴더의 글이 없으며 트리 캐시가 실습 폴더가 생기고 사라져도 맞다.
 * git 이 PATH 에 없으면 건너뛴다.
 */
class PracticeFolderRealGitTest {
    @TempDir lateinit var temp: Path

    private val workDir by lazy { File(temp.toFile(), "work") }
    private val logs = LogCapture()
    @AfterEach fun closeLogs() = logs.close()
    private val json = JsonMapper.builder().build()

    private val topic = "cs/algorithm/06-binary-search"
    private val practiceFolder = "$topic/practice/01-boundary-search"
    private val practiceProblem = "$practiceFolder/problem.md"
    private val practiceSolutionReadme = "$practiceFolder/solution/README.md"
    private val practiceMarker = "PRACTICE-SECRET-SOLUTION"
    private val topLevelPracticeDoc = "practice/programmers/그래프/01-가장 먼 노드/1-question.md"

    private lateinit var withoutPractice: String
    private lateinit var withPractice: String

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

    private fun write(path: String, text: String) {
        val file = File(workDir, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun commit(message: String): String {
        realGit(workDir, "add", "-A")
        realGit(workDir, "-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "commit", "-q", "-m", message)
        return realGit(workDir, "rev-parse", "HEAD")
    }

    private fun addPracticeFolder() {
        write(practiceProblem, "# 경계 찾기\n$practiceMarker problem\n")
        write("$practiceFolder/practice.json", """{"language": "python", "kind": "code", "title": "경계 찾기"}""" + "\n")
        write("$practiceFolder/starter/a.py", "def search(values, target):\n    raise NotImplementedError\n")
        write("$practiceFolder/tests/test_a.py", "from a import search\n\ndef test_search():\n    assert search([1], 1) == 0\n")
        write("$practiceFolder/solution/a.py", "def search(values, target):\n    return values.index(target)  # $practiceMarker\n")
        write(practiceSolutionReadme, "# 모범 답안\n$practiceMarker readme\n")
    }

    @BeforeEach
    fun createRepository() {
        val gitWorks = try { ProcessBuilder("git", "--version").start().waitFor() == 0 } catch (_: Exception) { false }
        assumeTrue(gitWorks, "git 이 PATH 에 없다")
        workDir.mkdirs()
        realGit(workDir, "init", "-q", "-b", "main")
        listOf(topic, "cs/algorithm/07-two-pointers").forEach { folder ->
            write("$folder/1-question.md", "# $folder 질문\n\n1. 무엇인가?\n")
            write("$folder/2-summary.md", "# $folder 정리\n\n본문\n")
            write("$folder/3-answer.md", "# $folder 정답\n\n### 1. 답\n\n답이다.\n")
        }
        write("practice/index.md", "# 연습 모음\n")
        write(topLevelPracticeDoc, "# 가장 먼 노드\n\n문제\n")
        write("practice/programmers/그래프/01-가장 먼 노드/3-answer.md", "# 가장 먼 노드 정답\n")
        write("docs/plan.md", "# 계획\n숨김 글\n")
        withoutPractice = commit("notes")
        addPracticeFolder()
        withPractice = commit("add practice folder")
    }

    private fun repository(directory: File = workDir) =
        GitRepository(directory, "git", requestTimeout = Duration.ofSeconds(10), syncTimeout = Duration.ofSeconds(30))

    private fun contentMvc(git: GitRepository, tree: TreeService = TreeService(git, logs.requestLog)) =
        MockMvcBuilders.standaloneSetup(ContentController(git, tree, logs.requestLog))
            .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    /** `/api/tree` 응답에서 트리 부분의 JSON 글자 그대로 */
    private fun treeJson(response: MockHttpServletResponse): String {
        assertEquals(200, response.status, response.contentAsString)
        val text = response.contentAsString
        assertTrue(text.contains("\"tree\":"), text)
        return text.substringAfter("\"tree\":").removeSuffix("}}")
    }

    private fun treeAfterRebuild(git: GitRepository, sha: String): String {
        val tree = TreeService(git, logs.requestLog).also { it.rebuild("req-tree", sha) }
        return treeJson(contentMvc(git, tree).perform(get("/api/tree")).andReturn().response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun findNode(node: Map<String, Any?>, path: String): Map<String, Any?>? {
        if (node["path"] == path) return node
        return (node["children"] as List<Map<String, Any?>>).firstNotNullOfOrNull { child -> findNode(child, path) }
    }

    @Test
    fun `준비 - 저장소에는 실습 폴더의 글이 실제로 있다`() {
        val listed = realGit(workDir, "-c", "core.quotepath=off", "ls-files", "*.md").lines()
        assertTrue(practiceProblem in listed && practiceSolutionReadme in listed, listed.toString())
    }

    // ---- 트리 ----

    @Test
    fun `실습 폴더가 생겨도 주제는 지금과 똑같이 주제로 뜨고 트리 JSON 이 바이트까지 같다`() {
        val git = repository()
        val treeWithPractice = treeAfterRebuild(git, withPractice)
        realGit(workDir, "checkout", "-q", withoutPractice)
        val treeWithoutPractice = treeAfterRebuild(git, withoutPractice)

        assertEquals(treeWithoutPractice, treeWithPractice, "실습 폴더가 트리를 바꿨다")
        val root = json.readValue(treeWithPractice, Map::class.java) as Map<String, Any?>
        val topicNode = findNode(root, topic)
        assertNotNull(topicNode, "주제가 트리에 없다")
        assertEquals(true, topicNode!!["is_subject"], "실습 폴더 때문에 주제가 주제로 뜨지 않는다: $topicNode")
        assertEquals(emptyList<Any>(), topicNode["children"])
        assertEquals(3, (topicNode["docs"] as List<*>).size)
        assertNotNull(findNode(root, "practice/programmers/그래프/01-가장 먼 노드"), "최상위 practice 의 기존 글이 트리에서 사라졌다")
        assertFalse(treeWithPractice.contains("01-boundary-search") || treeWithPractice.contains("problem.md"), treeWithPractice)
    }

    @Test
    fun `글 목록에 실습 폴더의 글이 없고 최상위 practice 의 기존 글은 그대로 있다`() {
        val git = repository()
        val listed = git.allMarkdown()
        assertFalse(listed.any { it.startsWith("$topic/practice/") }, "글 목록에 실습 폴더의 글이 있다: $listed")
        assertTrue(topLevelPracticeDoc in listed && "practice/index.md" in listed, "최상위 practice 의 기존 글이 사라졌다: $listed")

        val response = contentMvc(git).perform(get("/api/docs")).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        assertFalse(response.contentAsString.contains("01-boundary-search"), response.contentAsString)
        assertTrue(response.contentAsString.contains("\"count\":9"), "글 수가 실습 폴더가 없을 때(주제 6 + 최상위 practice 3)와 다르다: ${response.contentAsString}")
    }

    // ---- 문서 읽기 ----

    private fun docResponse(git: GitRepository, path: String, at: String? = null): MockHttpServletResponse {
        val request = get("/api/doc").param("path", path).also { builder -> if (at != null) builder.param("at", at) }
        return contentMvc(git).perform(request).andReturn().response
    }

    @Test
    fun `문서 읽기는 실습 폴더의 글을 숨김 글과 같은 응답으로 답하고 옛 판 읽기도 같다`() {
        val git = repository()
        val hidden = docResponse(git, "docs/plan.md")
        assertEquals(404, hidden.status, hidden.contentAsString)
        listOf(practiceProblem, practiceSolutionReadme).forEach { path ->
            listOf(null, withPractice, withPractice.take(7)).forEach { at ->
                val response = docResponse(git, path, at)
                assertEquals(hidden.status, response.status, "path=$path at=$at: ${response.contentAsString}")
                assertEquals(hidden.contentAsString, response.contentAsString, "path=$path at=$at — 숨김 글과 응답이 다르다")
                assertFalse(response.contentAsString.contains(practiceMarker))
            }
        }
    }

    @Test
    fun `주제의 글과 최상위 practice 의 기존 글은 그대로 읽힌다`() {
        val git = repository()
        listOf("$topic/2-summary.md", topLevelPracticeDoc).forEach { path ->
            val now = docResponse(git, path)
            assertEquals(200, now.status, "path=$path: ${now.contentAsString}")
            val old = docResponse(git, path, withPractice)
            assertEquals(200, old.status, "path=$path at=$withPractice: ${old.contentAsString}")
        }
    }

    // ---- 이력 ----

    private fun historyMvc(git: GitRepository) =
        MockMvcBuilders.standaloneSetup(HistoryController(HistoryService(git, logs.requestLog), logs.requestLog))
            .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    @Test
    fun `이력은 실습 폴더의 글을 숨김 글과 같은 응답으로 답하고 실습 폴더만 바꾼 커밋은 최근 변경에 없다`() {
        val git = repository()
        val mvc = historyMvc(git)
        val hidden = mvc.perform(get("/api/history/doc").param("path", "docs/plan.md")).andReturn().response
        assertEquals(404, hidden.status, hidden.contentAsString)
        listOf(practiceProblem, practiceSolutionReadme).forEach { path ->
            val response = mvc.perform(get("/api/history/doc").param("path", path)).andReturn().response
            assertEquals(hidden.status, response.status, "path=$path: ${response.contentAsString}")
            assertEquals(hidden.contentAsString, response.contentAsString, "path=$path — 숨김 글과 응답이 다르다")
        }
        val recent = git.recentChanges(30, 200, 50)
        assertFalse(recent.any { it.sha == withPractice }, "실습 폴더만 바꾼 커밋이 최근 변경에 있다: $recent")
        assertFalse(recent.flatMap { it.docs }.any { it.path.contains("/practice/01-") }, recent.toString())
        assertEquals(listOf(withoutPractice), git.documentHistory("$topic/1-question.md", 100).revisions.map { it.sha })
    }

    // ---- 동기화의 변경 목록 ----

    @Test
    fun `동기화의 변경 목록에 실습 폴더의 파일이 오지 않는다`() {
        val git = repository()
        assertEquals(emptyList<Pair<Char, String>>(), git.changedMarkdown(withoutPractice, withPractice), "실습 폴더만 생긴 커밋")
        File(workDir, "$topic/practice").deleteRecursively()
        write("$topic/2-summary.md", "# 정리\n\n고친 본문\n")
        val removed = commit("remove practice and edit summary")
        assertEquals(listOf('M' to "$topic/2-summary.md"), git.changedMarkdown(withPractice, removed), "실습 폴더가 사라지고 주제의 글이 바뀐 커밋")
    }

    // ---- 동기화를 거쳐 색인 · 트리 캐시 ----

    /** 경로마다 청크 수를 세는 가짜 색인 */
    private class FakeIndex : IndexStore {
        val documents = linkedMapOf<String, String>()
        override fun ensureIndex() = Unit
        override fun deleteByPath(path: String) { documents.keys.removeIf { it.substringBeforeLast('#') == path } }
        override fun bulkUpsert(documents: List<Pair<String, String>>) { documents.forEach { (id, source) -> this.documents[id] = source } }
        override fun countByPath(path: String): Long = documents.keys.count { it.substringBeforeLast('#') == path }.toLong()
        override fun deleteWhereShaNot(commitSha: String): Long = 0
        fun paths(): Set<String> = documents.keys.map { it.substringBeforeLast('#') }.toSet()
    }

    private val zeroEncoder = object : TextEncoder {
        override fun embed(texts: List<String>): List<List<Double>> = texts.map { List(1024) { 0.0 } }
    }

    @Test
    fun `동기화를 거쳐도 색인에 실습 폴더의 글이 없고 트리 캐시가 실습 폴더가 생기고 사라져도 같다`() {
        // 받아 쓰는 사본(backend 의 볼륨) — 원격은 workDir. 처음은 실습 폴더가 없는 판
        realGit(workDir, "checkout", "-q", "-b", "publish", withoutPractice)
        realGit(workDir, "branch", "-f", "main", withoutPractice)
        realGit(workDir, "checkout", "-q", "main")
        val copy = File(temp.toFile(), "copy")
        realGit(temp.toFile(), "clone", "-q", "file://${workDir.absolutePath}", copy.absolutePath)
        val git = repository(copy)
        val index = FakeIndex()
        val indexing = IndexingService(git, zeroEncoder, index, logs.requestLog, ObjectMapper())
        val tree = TreeService(git, logs.requestLog)
        val sync = SyncService(git, indexing, tree, logs.requestLog, mockk(relaxed = true))
        SyncService::class.java.getDeclaredField("stateFile").also { it.isAccessible = true }.set(sync, File(temp.toFile(), "state/last_sha"))

        fun syncTo(sha: String): Map<*, *> {
            assertTrue(sync.requestSync("req-sync-${sha.take(7)}", sha) is SyncService.Decision.Started)
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val status = sync.status()
                val result = status["last_result"] as Map<*, *>
                if (status["in_flight_sha"] == null && result["outcome"] != null) return result
                Thread.sleep(50)
            }
            fail<Unit>("동기화가 끝나지 않았다: ${sync.status()}")
            error("unreachable")
        }
        fun currentTree() = treeJson(contentMvc(git, tree).perform(get("/api/tree")).andReturn().response)

        assertEquals("ok", syncTo(withoutPractice)["outcome"])
        val baseTree = currentTree()
        val baseIndexed = index.paths()
        assertTrue("$topic/2-summary.md" in baseIndexed && topLevelPracticeDoc in baseIndexed, baseIndexed.toString())

        // 실습 폴더가 생긴다
        realGit(workDir, "reset", "-q", "--hard", withPractice)
        val created = syncTo(withPractice)
        assertEquals("ok", created["outcome"], created.toString())
        assertEquals(baseTree, currentTree(), "실습 폴더가 생긴 뒤 트리 캐시가 달라졌다")
        assertFalse(index.paths().any { it.contains("/practice/01-") }, "실습 폴더의 글이 색인되었다: ${index.paths()}")
        assertEquals(baseIndexed, index.paths())

        // 실습 폴더가 사라지고 주제의 글이 바뀐다
        File(workDir, "$topic/practice").deleteRecursively()
        write("$topic/2-summary.md", "# 정리\n\n고친 본문\n")
        val removed = commit("remove practice and edit summary")
        val gone = syncTo(removed)
        assertEquals("ok", gone["outcome"], gone.toString())
        assertEquals(baseTree, currentTree(), "실습 폴더가 사라진 뒤 트리 캐시가 달라졌다")
        assertEquals(baseIndexed, index.paths())
        assertTrue(index.documents.entries.any { (id, source) -> id.startsWith("$topic/2-summary.md#") && source.contains("고친 본문") }, "주제의 글이 다시 색인되지 않았다")
        assertTrue(logs.lines.none { it.startsWith("ERROR") && it.contains("sync failed") }, logs.lines.toString())
    }
}
