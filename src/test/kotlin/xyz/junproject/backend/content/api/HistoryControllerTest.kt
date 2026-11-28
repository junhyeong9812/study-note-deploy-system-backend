package xyz.junproject.backend.content.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.content.usecase.ChangeKind
import xyz.junproject.backend.content.usecase.ChangedDoc
import xyz.junproject.backend.content.usecase.CommitChanges
import xyz.junproject.backend.content.usecase.DocHistory
import xyz.junproject.backend.content.usecase.DocRevision
import xyz.junproject.backend.content.usecase.HistoryService
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import xyz.junproject.backend.shared.infra.RequestLog
import xyz.junproject.backend.sync.usecase.HistoryState
import java.io.FileNotFoundException

/**
 * 변경 이력 경로(명세 1 · 2절 · 8절 H1). 봉투는 기존 그대로(`success` · `data` / `error.code`).
 * - `GET /api/history/recent` → `data = { commit_sha, complete, history: { shallow, problem }, commits: [ { sha, at, title, docs: [ { path, kind, from? } ], more_docs } ] }`
 *   `kind` 는 added · modified · deleted · renamed, `from` 은 renamed 에만 있다.
 * - `GET /api/history/doc?path=` → `data = { path, complete, history: { shallow, problem }, revisions: [ { sha, at, title, path } ], more_revisions }`
 * - `complete` 는 저장소가 얕지 않다고 알려졌을 때만 참이다(얕음 · 모름이면 거짓 — 조용히 짧은 목록을 완전한 것처럼 내지 않는다).
 * - 경로 검증: 저장소 상대의 `.md` 가 아니거나(상위 폴더 · 절대 경로 · `~` · `.git` · 빈 값) 제어 문자가 있거나 1024자를 넘으면 422 invalid_request,
 *   숨김 폴더면 404 not_found(존재 은닉 — `/api/doc` 과 같다), 지금 판에 없는 글은 404 not_found. 거절된 경로로 git 을 부르지 않는다.
 * - git 의 장애 · 시간 초과 · 동시 읽기 상한 초과는 503 content_unavailable — 빈 목록 · 404 · 500 이 아니다.
 * - 옛 `GET /api/history`(커밋 제목 30개 — 숨김 커밋 포함)는 없다.
 */
class HistoryControllerTest {
    private val history = mockk<HistoryService>()
    private val requestLog = mockk<RequestLog>(relaxed = true)
    private val mockMvc = MockMvcBuilders
        .standaloneSetup(HistoryController(history, requestLog), ContentController(mockk(relaxed = true), mockk(relaxed = true), requestLog))
        .setControllerAdvice(GlobalErrorHandler(requestLog)).build()
    private val json = JsonMapper.builder().build()

    private val sha = "a".repeat(40)
    private val olderSha = "b".repeat(40)
    private val at = "2026-11-25T10:00:00+09:00"

    private fun body(response: MockHttpServletResponse): Map<*, *> = json.readValue(response.contentAsString, Map::class.java)
    private fun data(response: MockHttpServletResponse): Map<*, *> = body(response)["data"] as Map<*, *>
    private fun errorCode(response: MockHttpServletResponse): Any? = (body(response)["error"] as? Map<*, *>)?.get("code")

    private fun requestDoc(path: String): MockHttpServletResponse =
        mockMvc.perform(get("/api/history/doc").param("path", path)).andReturn().response

    private fun recentResponse(): MockHttpServletResponse = mockMvc.perform(get("/api/history/recent")).andReturn().response

    // ---- 최근 변경 ----

    @Test
    fun `최근 변경은 커밋마다 sha 시각 제목 글 목록 넘친 수만 싣고 글마다 경로와 구분을 싣는다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.recent(any()) } returns HistoryService.RecentChanges(sha, listOf(
            CommitChanges(sha, at, "move and edit", listOf(
                ChangedDoc("cs/c/2-summary.md", ChangeKind.RENAMED, from = "cs/a/2-summary.md"),
                ChangedDoc("cs/b/2-summary.md", ChangeKind.ADDED),
                ChangedDoc("cs/d/2-summary.md", ChangeKind.MODIFIED),
                ChangedDoc("cs/e/2-summary.md", ChangeKind.DELETED),
            ), 7),
        ))

        val response = recentResponse()

        assertEquals(200, response.status, response.contentAsString)
        assertEquals(true, body(response)["success"])
        val data = data(response)
        assertEquals(sha, data["commit_sha"])
        assertEquals(true, data["complete"])
        assertEquals(mapOf("shallow" to false, "problem" to null), data["history"])
        val commit = (data["commits"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("sha", "at", "title", "docs", "more_docs"), commit.keys, "커밋에 실린 칸: ${commit.keys}")
        assertEquals(sha, commit["sha"])
        assertEquals(at, commit["at"])
        assertEquals("move and edit", commit["title"])
        assertEquals(7, commit["more_docs"])
        assertEquals(
            listOf(
                mapOf("path" to "cs/c/2-summary.md", "kind" to "renamed", "from" to "cs/a/2-summary.md"),
                mapOf("path" to "cs/b/2-summary.md", "kind" to "added"),
                mapOf("path" to "cs/d/2-summary.md", "kind" to "modified"),
                mapOf("path" to "cs/e/2-summary.md", "kind" to "deleted"),
            ),
            commit["docs"],
        )
    }

    @Test
    fun `최근 변경의 git 장애는 빈 목록이 아니라 503 content_unavailable 이다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.recent(any()) } throws IllegalStateException("git timeout(5000ms): log")

        val response = recentResponse()

        assertEquals(503, response.status, response.contentAsString)
        assertEquals(false, body(response)["success"])
        assertEquals("content_unavailable", errorCode(response))
        assertFalse(response.contentAsString.contains("commits"), response.contentAsString)
    }

    @Test
    fun `저장소가 얕거나 얕은지 모르면 최근 변경은 완전하지 않다고 말하고 그 문구를 싣는다`() {
        every { history.recent(any()) } returns HistoryService.RecentChanges(sha, emptyList())
        every { history.historyState() } returns HistoryState(shallow = true, problem = "받기는 끝났으나 저장소가 아직 얕다")
        val shallow = data(recentResponse())
        assertEquals(false, shallow["complete"])
        assertEquals(mapOf("shallow" to true, "problem" to "받기는 끝났으나 저장소가 아직 얕다"), shallow["history"])

        every { history.historyState() } returns HistoryState.UNKNOWN
        val unknown = data(recentResponse())
        assertEquals(false, unknown["complete"], "얕은지 모르는데 완전하다고 말한다: $unknown")
        assertEquals(mapOf("shallow" to null, "problem" to null), unknown["history"])
    }

    // ---- 글 하나의 변경 목록 ----

    @Test
    fun `글 하나의 변경 목록은 판마다 sha 시각 제목 그 판의 경로만 싣는다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.documentHistory(any(), "cs/new/2-summary.md") } returns DocHistory(listOf(
            DocRevision(sha, at, "edit new", "cs/new/2-summary.md"),
            DocRevision(olderSha, "2026-11-24T10:00:00+09:00", "create old", "cs/old/2-summary.md"),
        ), 3)

        val response = requestDoc("cs/new/2-summary.md")

        assertEquals(200, response.status, response.contentAsString)
        val data = data(response)
        assertEquals("cs/new/2-summary.md", data["path"])
        assertEquals(true, data["complete"])
        assertEquals(3, data["more_revisions"])
        assertEquals(
            listOf(
                mapOf("sha" to sha, "at" to at, "title" to "edit new", "path" to "cs/new/2-summary.md"),
                mapOf("sha" to olderSha, "at" to "2026-11-24T10:00:00+09:00", "title" to "create old", "path" to "cs/old/2-summary.md"),
            ),
            data["revisions"],
        )
    }

    @Test
    fun `얕은 저장소에서 글 하나의 변경 목록은 완전하지 않다고 말한다`() {
        every { history.historyState() } returns HistoryState(shallow = true, problem = "git 실패(fetch --unshallow origin main): timeout")
        every { history.documentHistory(any(), any()) } returns DocHistory(listOf(DocRevision(sha, at, "only one", "cs/a/2-summary.md")), 0)

        val data = data(requestDoc("cs/a/2-summary.md"))

        assertEquals(false, data["complete"])
        assertEquals(mapOf("shallow" to true, "problem" to "git 실패(fetch --unshallow origin main): timeout"), data["history"])
    }

    @Test
    fun `저장소 상대의 md 가 아닌 경로와 제어 문자와 너무 긴 경로는 422 이고 git 을 부르지 않는다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        val rejected = listOf(
            "../x.md", "cs/../../x.md", "/etc/passwd.md", "~/x.md", ".git/config.md", "cs/.git/x.md",
            "cs/a/2-summary.txt", "cs/a", " ", "cs/a\u0000.md", "cs/a\n.md", "cs/a\u001b.md",
            "cs/" + "a".repeat(1022) + ".md",
        )
        rejected.forEach { path ->
            val response = requestDoc(path)
            assertEquals(422, response.status, "거절해야 할 경로($path): ${response.contentAsString}")
            assertEquals("invalid_request", errorCode(response), path)
        }
        verify(exactly = 0) { history.documentHistory(any(), any()) }
    }

    @Test
    fun `주소에서 풀린 상위 폴더 꼴도 거절하고 두 번 감싼 글자는 한 번만 풀어 그대로 넘긴다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.documentHistory(any(), any()) } throws FileNotFoundException("missing")

        val decoded = mockMvc.perform(get(java.net.URI.create("/api/history/doc?path=cs%2F..%2F..%2Fx.md"))).andReturn().response
        assertEquals(422, decoded.status, decoded.contentAsString)

        val doubled = mockMvc.perform(get(java.net.URI.create("/api/history/doc?path=cs%252F..%252Fx.md"))).andReturn().response
        assertEquals(404, doubled.status, doubled.contentAsString)
        verify(exactly = 1) { history.documentHistory(any(), "cs%2F..%2Fx.md") }
    }

    @Test
    fun `길이 1024 자의 경로는 받는다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        val path = "cs/" + "a".repeat(1018) + ".md"
        assertEquals(1024, path.length)
        every { history.documentHistory(any(), path) } returns DocHistory(emptyList(), 0)
        assertEquals(200, requestDoc(path).status)
    }

    @Test
    fun `숨김 폴더의 글은 404 not_found 이고 git 을 부르지 않는다`() {
        listOf("docs/plan.md", "templates/note.md").forEach { path ->
            val response = requestDoc(path)
            assertEquals(404, response.status, path)
            assertEquals("not_found", errorCode(response), path)
        }
        verify(exactly = 0) { history.documentHistory(any(), any()) }
    }

    @Test
    fun `지금 판에 없는 글은 404 not_found 다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.documentHistory(any(), "cs/none/2-summary.md") } throws FileNotFoundException("cs/none/2-summary.md")
        val response = requestDoc("cs/none/2-summary.md")
        assertEquals(404, response.status, response.contentAsString)
        assertEquals("not_found", errorCode(response))
    }

    @Test
    fun `글 하나의 변경 목록의 git 장애는 빈 목록 404 500 이 아니라 503 content_unavailable 이다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.documentHistory(any(), any()) } throws IllegalStateException("git timeout(5000ms): log")
        val response = requestDoc("cs/a/2-summary.md")
        assertEquals(503, response.status, response.contentAsString)
        assertEquals("content_unavailable", errorCode(response))
        assertFalse(response.contentAsString.contains("revisions"), response.contentAsString)
    }

    @Test
    fun `옵션처럼 보이는 경로도 형식이 맞으면 글자 그대로 넘긴다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.documentHistory(any(), any()) } throws FileNotFoundException("missing")
        assertEquals(404, requestDoc("--output=x.md").status)
        verify(exactly = 1) { history.documentHistory(any(), "--output=x.md") }
    }

    /**
     * pathspec 마법처럼 보이는 경로를 경로 검증이 어디서 거르는가 — 422 는 git 까지 가지 않고, 나머지는 글자 그대로 넘긴다
     * (마법을 끄는 것은 git 을 부르는 쪽의 일이다 — GitRepositoryChangesRealGitTest).
     */
    @Test
    fun `pathspec 마법처럼 보이는 경로 가운데 형식이 틀린 것만 422 이고 나머지는 글자 그대로 넘긴다`() {
        every { history.historyState() } returns HistoryState(shallow = false, problem = null)
        every { history.documentHistory(any(), any()) } throws FileNotFoundException("missing")
        val expected = linkedMapOf(
            "cs/*.md" to 404,
            "cs/?ne.md" to 404,
            "cs/[o]ne.md" to 404,
            ":(glob)cs/*.md" to 404,
            ":(top)cs/one.md" to 404,
            ":/cs/one.md" to 404,
            ":(icase)CS/ONE.md" to 404,
            ":(exclude)cs/one.md" to 404,
            ":(glob)cs/**" to 422,
            ":(top)/cs/one.md" to 404,
            "/cs/one.md" to 422,
            ":(top)../cs/one.md" to 404,
            "cs/../cs/one.md" to 422,
        )
        expected.forEach { (path, status) ->
            val response = requestDoc(path)
            assertEquals(status, response.status, "경로($path): ${response.contentAsString}")
            if (status == 422) verify(exactly = 0) { history.documentHistory(any(), path) }
            else verify(exactly = 1) { history.documentHistory(any(), path) }
        }
    }

    @Test
    fun `경로가 없으면 422 invalid_request 다`() {
        val response = mockMvc.perform(get("/api/history/doc")).andReturn().response
        assertEquals(422, response.status, response.contentAsString)
        assertEquals("invalid_request", errorCode(response))
    }

    @Test
    fun `옛 경로 api history 는 더 이상 커밋 목록을 내지 않는다`() {
        // 없는 경로의 꼴(404 · 전역 처리기의 봉투)은 이 작업이 정하지 않는다 — 성공으로 커밋 목록을 내지 않는 것만 본다
        val response = mockMvc.perform(get("/api/history")).andReturn().response
        assertNotEquals(200, response.status, response.contentAsString)
        assertFalse(response.contentAsString.contains("commits"), response.contentAsString)
    }
}
