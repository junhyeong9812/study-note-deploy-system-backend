package xyz.junproject.backend.shared.infra

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.content.api.HistoryController
import xyz.junproject.backend.content.usecase.ChangeKind
import xyz.junproject.backend.content.usecase.ChangedDoc
import xyz.junproject.backend.content.usecase.CommitChanges
import xyz.junproject.backend.content.usecase.DocRevision
import xyz.junproject.backend.content.usecase.HistoryService
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 최근 변경과 글 하나의 변경 목록을 실제 git 으로(명세 1 · 2절, 8절 H1).
 * - 최근 변경: 공개되는 글(숨김 폴더 `docs` · `templates` 밖의 `.md`)을 하나라도 바꾼 커밋만 새것부터. 병합 커밋은 싣지 않는다(병합된 쪽의 커밋이 제 이름으로 실린다).
 *   글마다 더해짐 · 고쳐짐 · 지워짐 · 옮겨짐. 숨김 폴더에서 나온 글은 더해짐(옛 경로를 싣지 않는다), 숨김 폴더로 들어간 글은 지워짐.
 *   상한: 실을 커밋 수 · 훑을 커밋 수 · 커밋당 글 수(넘으면 뺀 수). 빈 저장소는 빈 목록.
 * - 글 하나의 변경 목록: 이름 바뀜을 따라간다(그 판의 경로를 싣는다). 숨김 폴더에 있던 때의 판은 싣지 않는다. 지금 판에 없는 글은 FileNotFoundException.
 * - 어느 쪽이든 커밋의 제목만 — 본문 · 글쓴이 · 메일은 결과 어디에도 없다.
 * git 이 PATH 에 없으면 건너뛴다. 이 테스트의 git 은 사용자 설정을 읽지 않는다.
 */
class GitRepositoryChangesRealGitTest {
    @TempDir lateinit var temp: Path

    private val repoDir by lazy { File(temp.toFile(), "repo") }
    private var clock = 0L

    private val authorName = "Secret Author"
    private val authorEmail = "author-secret@example.invalid"
    private val committerName = "Hidden Committer"
    private val committerEmail = "committer-secret@example.invalid"
    private val bodyLine = "body-secret-line"
    private val forbiddenWords = listOf(authorName, authorEmail, committerName, committerEmail, bodyLine, "Signed-off-by")

    @BeforeEach
    fun requireGit() {
        assumeTrue(commandWorks("git", "--version"), "git 이 PATH 에 없다 — 실제 git 테스트를 건너뛴다")
        repoDir.mkdirs()
        realGit("init", "-q", "-b", "main")
    }

    private fun commandWorks(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    private fun realGit(vararg arguments: String, environment: Map<String, String> = emptyMap(), directory: File = repoDir): String {
        val builder = ProcessBuilder("git", *arguments).directory(directory).redirectErrorStream(true)
        builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        builder.environment()["LC_ALL"] = "C"
        builder.environment().putAll(environment)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "준비의 git 이 끝나지 않았다: ${arguments.joinToString(" ")}")
        assertEquals(0, process.exitValue(), "준비의 git 이 실패했다(${arguments.joinToString(" ")}): $output")
        return output.trim()
    }

    private fun write(path: String, text: String) {
        val file = File(repoDir, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun remove(path: String) = realGit("rm", "-q", "--", path)

    private fun move(from: String, to: String) {
        File(repoDir, to).parentFile.mkdirs()
        realGit("mv", "--", from, to)
    }

    /** [change] 를 한 뒤 모두 담아 커밋한다 — 시각은 1분씩 늘고, 본문에는 서명 줄(메일)과 비밀 낱말을 둔다. 커밋의 sha */
    private fun commit(title: String, change: () -> Unit): String {
        change()
        realGit("add", "-A")
        clock += 60
        val date = "@${1_764_000_000 + clock} +0900"
        val message = File(temp.toFile(), "message.txt")
        message.writeText("$title\n\n$bodyLine\n\nSigned-off-by: $authorName <$authorEmail>\n")
        realGit(
            "commit", "-q", "-F", message.absolutePath,
            environment = mapOf(
                "GIT_AUTHOR_NAME" to authorName, "GIT_AUTHOR_EMAIL" to authorEmail, "GIT_AUTHOR_DATE" to date,
                "GIT_COMMITTER_NAME" to committerName, "GIT_COMMITTER_EMAIL" to committerEmail, "GIT_COMMITTER_DATE" to date,
            ),
        )
        return realGit("rev-parse", "HEAD")
    }

    private fun committedAt(sha: String): String = realGit("log", "-1", "--format=%cI", sha)

    private fun repository() = GitRepository(repoDir, "git", requestTimeout = Duration.ofSeconds(10), syncTimeout = Duration.ofSeconds(30))

    private fun assertNoForbiddenWords(result: Any) {
        val text = result.toString()
        forbiddenWords.forEach { word -> assertFalse(text.contains(word), "내보내지 않기로 한 것($word)이 결과에 있다: $text") }
    }

    private fun recent(commitLimit: Int = 30, scanLimit: Int = 200, docLimit: Int = 50): List<CommitChanges> =
        repository().recentChanges(commitLimit, scanLimit, docLimit)

    // ---- 최근 변경 ----

    @Test
    fun `최근 변경은 새것부터 커밋의 sha 시각 제목과 더해짐 고쳐짐 지워짐 옮겨짐을 싣는다`() {
        val first = commit("add two notes") {
            write("cs/a/2-summary.md", "# a\n")
            write("cs/b/2-summary.md", "# b\n")
        }
        val second = commit("edit a and drop b") {
            write("cs/a/2-summary.md", "# a\nmore\n")
            remove("cs/b/2-summary.md")
        }
        val third = commit("move a to c") { move("cs/a/2-summary.md", "cs/c/2-summary.md") }

        val changes = recent()

        assertEquals(listOf(third, second, first), changes.map { it.sha })
        assertEquals(listOf("move a to c", "edit a and drop b", "add two notes"), changes.map { it.title })
        assertEquals(listOf(committedAt(third), committedAt(second), committedAt(first)), changes.map { it.at })
        assertEquals(listOf(ChangedDoc("cs/c/2-summary.md", ChangeKind.RENAMED, from = "cs/a/2-summary.md")), changes[0].docs)
        assertEquals(
            setOf(ChangedDoc("cs/a/2-summary.md", ChangeKind.MODIFIED), ChangedDoc("cs/b/2-summary.md", ChangeKind.DELETED)),
            changes[1].docs.toSet(),
        )
        assertEquals(
            setOf(ChangedDoc("cs/a/2-summary.md", ChangeKind.ADDED), ChangedDoc("cs/b/2-summary.md", ChangeKind.ADDED)),
            changes[2].docs.toSet(),
        )
        assertTrue(changes.all { it.moreDocs == 0 }, changes.toString())
        assertTrue(changes.all { it.sha.matches(Regex("^[0-9a-f]{40}$")) }, changes.toString())
    }

    @Test
    fun `최근 변경에는 커밋의 본문 글쓴이 메일이 없다`() {
        commit("plain title") { write("cs/a/2-summary.md", "# a\n") }
        val changes = recent()
        assertEquals("plain title", changes.single().title)
        assertNoForbiddenWords(changes)
    }

    @Test
    fun `숨김 폴더와 글이 아닌 파일은 빠지고 숨김 폴더만 고친 커밋은 통째로 빠진다`() {
        val hiddenOnly = commit("plans only") {
            write("docs/plan.md", "# plan\n")
            write("templates/note.md", "# template\n")
            write("docs/moving.md", "# moving out\n")
        }
        val mixed = commit("mixed") {
            write("cs/x/2-summary.md", "# x\n")
            write("cs/x/image.png", "not a note")
            write("README.txt", "readme")
            write("docs/other.md", "# other\n")
            write("index.md", "# index\n")
        }
        val outOfHidden = commit("publish moving") { move("docs/moving.md", "cs/moving/2-summary.md") }
        val intoHidden = commit("retire x") { move("cs/x/2-summary.md", "docs/x.md") }

        val changes = recent()

        assertFalse(changes.any { it.sha == hiddenOnly }, "숨김 폴더만 고친 커밋이 실렸다: $changes")
        assertEquals(listOf(intoHidden, outOfHidden, mixed), changes.map { it.sha })
        assertEquals(setOf(ChangedDoc("cs/x/2-summary.md", ChangeKind.ADDED), ChangedDoc("index.md", ChangeKind.ADDED)), changes[2].docs.toSet())
        assertEquals(listOf(ChangedDoc("cs/moving/2-summary.md", ChangeKind.ADDED)), changes[1].docs, "숨김 폴더에서 나온 글은 옛 경로 없이 더해짐이다")
        assertEquals(listOf(ChangedDoc("cs/x/2-summary.md", ChangeKind.DELETED)), changes[0].docs, "숨김 폴더로 들어간 글은 지워짐이다")
        val everyPath = changes.flatMap { commitChanges -> commitChanges.docs.flatMap { listOfNotNull(it.path, it.from) } }
        assertTrue(everyPath.none { it.startsWith("docs/") || it.startsWith("templates/") }, "숨김 경로가 실렸다: $everyPath")
        assertTrue(everyPath.all { it.endsWith(".md") }, "글이 아닌 파일이 실렸다: $everyPath")
    }

    @Test
    fun `실을 커밋 수와 훑을 커밋 수와 커밋당 글 수의 상한을 지키고 넘친 글의 수를 센다`() {
        val many = commit("seven notes and hidden ones") {
            (1..7).forEach { number -> write("cs/many/$number.md", "# $number\n") }
            (1..3).forEach { number -> write("docs/many-$number.md", "# hidden $number\n") }
        }
        commit("hidden only") { write("docs/later.md", "# later\n") }
        val third = commit("edit one") { write("cs/many/1.md", "# 1 edited\n") }
        val fourth = commit("edit two") { write("cs/many/2.md", "# 2 edited\n") }

        assertEquals(listOf(fourth, third), recent(commitLimit = 2).map { it.sha }, "실을 커밋 수의 상한")
        assertEquals(listOf(fourth, third), recent(scanLimit = 2).map { it.sha }, "훑을 커밋 수의 상한")
        assertEquals(listOf(fourth, third), recent(scanLimit = 3).map { it.sha }, "숨김 폴더만 고친 커밋도 훑은 수에 든다")

        val withMany = recent(scanLimit = 4, docLimit = 5)
        assertEquals(listOf(fourth, third, many), withMany.map { it.sha })
        assertEquals(5, withMany[2].docs.size, withMany[2].toString())
        assertEquals(2, withMany[2].moreDocs, "넘친 글의 수는 공개되는 글만 센다: ${withMany[2]}")
        assertTrue(withMany[2].docs.all { it.path.startsWith("cs/many/") && it.kind == ChangeKind.ADDED }, withMany[2].toString())
    }

    @Test
    fun `병합 커밋은 싣지 않고 병합된 쪽의 커밋은 제 이름으로 실린다`() {
        val base = commit("base") { write("cs/a/2-summary.md", "# a\n") }
        realGit("checkout", "-q", "-b", "feature")
        val featureWork = commit("feature work") { write("cs/f/2-summary.md", "# f\n") }
        realGit("checkout", "-q", "main")
        val mainWork = commit("main work") { write("cs/a/2-summary.md", "# a\nmain\n") }
        clock += 60
        val mergeDate = "@${1_764_000_000 + clock} +0900"
        realGit(
            "merge", "-q", "--no-ff", "-m", "Merge feature", "feature",
            environment = mapOf(
                "GIT_AUTHOR_NAME" to authorName, "GIT_AUTHOR_EMAIL" to authorEmail, "GIT_AUTHOR_DATE" to mergeDate,
                "GIT_COMMITTER_NAME" to committerName, "GIT_COMMITTER_EMAIL" to committerEmail, "GIT_COMMITTER_DATE" to mergeDate,
            ),
        )
        val merge = realGit("rev-parse", "HEAD")

        val changes = recent()

        assertFalse(changes.any { it.sha == merge }, "병합 커밋이 실렸다: $changes")
        assertEquals(setOf(base, featureWork, mainWork), changes.map { it.sha }.toSet())
        assertEquals(listOf(ChangedDoc("cs/f/2-summary.md", ChangeKind.ADDED)), changes.single { it.sha == featureWork }.docs)
    }

    @Test
    fun `빈 저장소의 최근 변경은 빈 목록이고 글 하나의 이력은 없음이다`() {
        assertEquals(emptyList<CommitChanges>(), recent())
        assertThrows(FileNotFoundException::class.java) { repository().documentHistory("cs/a/2-summary.md", 100) }
    }

    // ---- 글 하나의 변경 목록 ----

    @Test
    fun `글 하나의 변경 목록은 이름 바뀜을 따라가 그 판의 경로와 함께 새것부터 싣는다`() {
        val first = commit("create old") { write("cs/old/2-summary.md", "# note\nline one\nline two\nline three\n") }
        val second = commit("edit old") { write("cs/old/2-summary.md", "# note\nline one\nline two\nline three\nline four\n") }
        val third = commit("rename to new") { move("cs/old/2-summary.md", "cs/new/2-summary.md") }
        val fourth = commit("edit new") { write("cs/new/2-summary.md", "# note\nline one\nline two\nline three\nline four\nline five\n") }
        commit("unrelated") { write("cs/other/2-summary.md", "# other\n") }

        val history = repository().documentHistory("cs/new/2-summary.md", 100)

        assertEquals(
            listOf(
                DocRevision(fourth, committedAt(fourth), "edit new", "cs/new/2-summary.md"),
                DocRevision(third, committedAt(third), "rename to new", "cs/new/2-summary.md"),
                DocRevision(second, committedAt(second), "edit old", "cs/old/2-summary.md"),
                DocRevision(first, committedAt(first), "create old", "cs/old/2-summary.md"),
            ),
            history.revisions,
        )
        assertEquals(0, history.moreRevisions)
        assertNoForbiddenWords(history)

        val limited = repository().documentHistory("cs/new/2-summary.md", 2)
        assertEquals(listOf(fourth, third), limited.revisions.map { it.sha })
        assertEquals(2, limited.moreRevisions)
    }

    @Test
    fun `글이 숨김 폴더에 있던 때의 판은 글 하나의 변경 목록에 싣지 않는다`() {
        commit("draft") { write("docs/draft.md", "# draft\nline one\nline two\nline three\n") }
        commit("edit draft") { write("docs/draft.md", "# draft\nline one\nline two\nline three\nline four\n") }
        val published = commit("publish draft") { move("docs/draft.md", "cs/d/2-summary.md") }
        val edited = commit("edit published") { write("cs/d/2-summary.md", "# draft\nline one\nline two\nline three\nline four\nmore\n") }

        val history = repository().documentHistory("cs/d/2-summary.md", 100)

        assertEquals(listOf(edited, published), history.revisions.map { it.sha })
        assertTrue(history.revisions.all { it.path == "cs/d/2-summary.md" }, history.toString())
        assertEquals(0, history.moreRevisions, "숨김 폴더에 있던 판은 넘친 수에도 들지 않는다")
    }

    @Test
    fun `지금 판에 없는 글은 지워졌든 처음부터 없었든 없음이다`() {
        commit("add then") { write("cs/gone/2-summary.md", "# gone\n") }
        commit("remove") { remove("cs/gone/2-summary.md") }
        assertThrows(FileNotFoundException::class.java) { repository().documentHistory("cs/gone/2-summary.md", 100) }
        assertThrows(FileNotFoundException::class.java) { repository().documentHistory("cs/never/2-summary.md", 100) }
    }

    @Test
    fun `옵션처럼 보이는 경로도 git 의 옵션으로 읽히지 않는다`() {
        val optionLike = commit("option-like name") { write("--all.md", "# dashes\n") }
        commit("another note") { write("cs/a/2-summary.md", "# a\n") }

        val history = repository().documentHistory("--all.md", 100)
        assertEquals(listOf(optionLike), history.revisions.map { it.sha }, "경로가 옵션(--all)으로 읽혔다: $history")

        val outputTarget = File(temp.toFile(), "pwn.md")
        assertThrows(FileNotFoundException::class.java) { repository().documentHistory("--output=${outputTarget.absolutePath}", 100) }
        assertFalse(outputTarget.exists(), "경로가 --output 옵션으로 읽혀 파일이 생겼다")
    }

    // pathspec 의 마법(`*` · `?` · `[...]` 의 글로브, `:(top)` · `:/` · `:(glob)` · `:(icase)`)은 꺼져 있다 — 경로는 글자 그대로의 파일 하나만 가리킨다
    private val pathspecMagic = listOf("cs/*.md", "cs/?ne.md", "cs/[o]ne.md", ":(glob)cs/*.md", ":(top)cs/one.md", ":/cs/one.md", ":(icase)CS/ONE.md")

    @Test
    fun `글자 그대로의 파일이 없으면 pathspec 마법을 쓴 경로는 다른 글의 판을 하나도 돌려주지 않고 없음이다`() {
        commit("add one") { write("cs/one.md", "# one\n") }
        commit("add two") { write("cs/two.md", "# two\n") }
        commit("edit one") { write("cs/one.md", "# one\nmore\n") }

        pathspecMagic.forEach { path ->
            val outcome = try { repository().documentHistory(path, 100) } catch (error: Exception) { error }
            assertTrue(outcome is FileNotFoundException, "pathspec 마법이 켜져 있다 — '$path' 가 없음이 아니다: $outcome")
        }
    }

    @Test
    fun `글자 그대로 별표가 든 파일이 있으면 그 파일의 커밋만 돌려준다`() {
        val literal = commit("add literal star") {
            write("cs/one.md", "# one\n")
            write("cs/*.md", "# literally a star\n")
        }
        commit("add two") { write("cs/two.md", "# two\n") }
        commit("edit one") { write("cs/one.md", "# one\nmore\n") }

        val history = repository().documentHistory("cs/*.md", 100)

        assertEquals(listOf(literal), history.revisions.map { it.sha }, "글로브가 다른 글의 판을 끌어왔다: $history")
        assertEquals(listOf("cs/*.md"), history.revisions.map { it.path })
    }

    // ---- 제목에 든 0x1e(기록 구분 글자) ----
    // 제목은 git 이 준 그대로 싣는다(0x1e 포함). 출력의 해석이 제목의 글자에 기대지 않는다 — 커밋이 빠지거나 가짜 커밋이 생기거나 글이 다른 커밋에 붙지 않는다.
    // 제목 안에 40자 16진처럼 보이는 조각이 있어도 같다. NUL 은 git 이 커밋 메시지에 받지 않아 대상 밖이다.
    private val separator = "\u001e"
    private val shaLikeTail = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `제목에 기록 구분 글자가 든 커밋도 최근 변경에서 빠지지 않고 제목이 통째로 실리며 앞뒤 커밋이 어긋나지 않는다`() {
        val before = commit("before") { write("cs/before.md", "# before\n") }
        val single = commit("one${separator}doc") { write("cs/single.md", "# single\n") }
        val multiple = commit("two${separator}docs") {
            write("cs/before.md", "# before\nedited\n")
            write("cs/second.md", "# second\n")
        }
        val shaLike = commit("looks${separator}$shaLikeTail") {
            write("cs/third.md", "# third\n")
            write("cs/fourth.md", "# fourth\n")
        }
        val after = commit("after") { write("cs/after.md", "# after\n") }

        val changes = recent()

        assertTrue(changes.all { it.sha.matches(Regex("^[0-9a-f]{40}$")) }, "가짜 커밋이 생겼다: ${changes.map { it.sha }}")
        assertEquals(listOf(after, shaLike, multiple, single, before), changes.map { it.sha })
        assertEquals(listOf("after", "looks${separator}$shaLikeTail", "two${separator}docs", "one${separator}doc", "before"), changes.map { it.title })
        assertEquals(listOf(ChangedDoc("cs/after.md", ChangeKind.ADDED)), changes[0].docs)
        assertEquals(setOf(ChangedDoc("cs/third.md", ChangeKind.ADDED), ChangedDoc("cs/fourth.md", ChangeKind.ADDED)), changes[1].docs.toSet())
        assertEquals(setOf(ChangedDoc("cs/before.md", ChangeKind.MODIFIED), ChangedDoc("cs/second.md", ChangeKind.ADDED)), changes[2].docs.toSet())
        assertEquals(listOf(ChangedDoc("cs/single.md", ChangeKind.ADDED)), changes[3].docs)
        assertEquals(listOf(ChangedDoc("cs/before.md", ChangeKind.ADDED)), changes[4].docs)
        assertEquals(listOf(committedAt(after), committedAt(shaLike), committedAt(multiple), committedAt(single), committedAt(before)), changes.map { it.at })
    }

    @Test
    fun `제목에 기록 구분 글자가 든 커밋도 글 하나의 변경 목록에서 빠지지 않고 앞뒤 판이 어긋나지 않는다`() {
        val first = commit("create") { write("cs/note.md", "# note\n") }
        val odd = commit("edit${separator}$shaLikeTail") {
            write("cs/note.md", "# note\nedited\n")
            write("cs/other.md", "# other\n")
        }
        val last = commit("edit again${separator}") { write("cs/note.md", "# note\nedited\nagain\n") }

        val history = repository().documentHistory("cs/note.md", 100)

        assertEquals(
            listOf(
                DocRevision(last, committedAt(last), "edit again$separator", "cs/note.md"),
                DocRevision(odd, committedAt(odd), "edit${separator}$shaLikeTail", "cs/note.md"),
                DocRevision(first, committedAt(first), "create", "cs/note.md"),
            ),
            history.revisions,
        )
    }

    // ---- 얕은 저장소: 이력 응답이 "다 갖고 있지 않다"를 싣는다 (H0 과 잇는 곳) ----

    @Test
    fun `얕은 원격에서 받아 아직 얕은 저장소의 이력 응답은 완전하지 않다고 말한다`() {
        repeat(6) { number -> commit("edit $number") { write("cs/a/2-summary.md", "# a\n" + "line\n".repeat(number + 1)) } }
        val shallowRemote = File(temp.toFile(), "shallow-remote.git")
        realGit("clone", "-q", "--bare", "--depth", "3", "file://${repoDir.absolutePath}", shallowRemote.absolutePath, directory = temp.toFile())
        val shallowCopy = File(temp.toFile(), "copy")
        realGit("clone", "-q", "--depth", "2", "file://${shallowRemote.absolutePath}", shallowCopy.absolutePath, directory = temp.toFile())

        val git = GitRepository(shallowCopy, "git", requestTimeout = Duration.ofSeconds(10), syncTimeout = Duration.ofSeconds(30))
        git.syncToRemoteHead()
        val requestLog = mockk<RequestLog>(relaxed = true)
        val mockMvc = MockMvcBuilders.standaloneSetup(HistoryController(HistoryService(git, requestLog), requestLog))
            .setControllerAdvice(GlobalErrorHandler(requestLog)).build()

        val response = mockMvc.perform(get("/api/history/doc").param("path", "cs/a/2-summary.md")).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        val data = JsonMapper.builder().build().readValue(response.contentAsString, Map::class.java)["data"] as Map<*, *>
        assertEquals(false, data["complete"], "얕은 저장소인데 완전하다고 말한다: $data")
        val history = data["history"] as Map<*, *>
        assertEquals(true, history["shallow"], data.toString())
        assertTrue((history["problem"] as? String)?.contains("아직 얕다") == true, data.toString())
        assertTrue((data["revisions"] as List<*>).isNotEmpty(), data.toString())
    }
}
