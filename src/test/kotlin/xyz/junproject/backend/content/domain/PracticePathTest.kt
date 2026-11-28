package xyz.junproject.backend.content.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 실습 경로(명세 ⑭ §7 · §10 · §11 P3a) — 주제 폴더 안의 `practice/` 아래는 글 읽기 길(트리 · 문서 · 검색 색인 · 채팅 · 이력 · 동기화의 변경 목록)에서 숨긴다.
 *
 * 정의: 경로의 **폴더 조각** 가운데 둘째 이후의 것이 정확히 `practice` 이면 실습 경로다.
 * - 첫 조각(최상위)의 `practice/` 는 실습 자리가 아니다 — 글 저장소에는 이미 최상위 `practice/programmers/…` 의 글(142개)과 `practice/index.md` 가 있다.
 *   실습 자리는 주제 폴더(리프) 안이므로 최상위일 수 없다(글 저장소의 규칙 §8 — "주제 폴더가 아닌 곳의 `practice/` 는 틀림").
 * - 파일 이름 `practice.md` · `practice.json` 은 폴더 조각이 아니다.
 * - 대소문자는 정확히 `practice` 만 — `Practice` 는 실습 자리가 아니다(검사기가 주제 폴더의 틀린 하위 폴더로 잡는다).
 * - `practice/` 바로 아래의 꼴(`<두 자리 번호>-<이름>`)은 보지 않는다 — 그 아래 무엇이든 숨긴다(틀린 꼴도 사이트에 새지 않게).
 * 실습 경로는 숨김 판정([PathGuard.isHidden])에도 든다 — 숨김 폴더를 거르는 모든 곳이 함께 거른다.
 */
class PracticePathTest {
    private val practicePaths = listOf(
        "cs/algorithm/06-binary-search/practice/01-boundary-search/problem.md",
        "cs/data-structure/10-lru-cache/practice/01-lru-cache/problem.md",
        "cs/data-structure/10-lru-cache/practice/01-lru-cache/solution/README.md",
        "cs/data-structure/10-lru-cache/practice/01-lru-cache/starter/lru/cache.py",
        "cs/algorithm/06-binary-search/practice/01-boundary-search/tests/test_boundaries.py",
        "cs/algorithm/06-binary-search/practice/01-boundary-search/practice.json",
        "cs/x/practice/README.md",
        "cs/x/practice/wrong-shape/notes.md",
        "history/java/streams/practice/01-collectors/problem.md",
        "languages/kotlin/basics/practice/01-hello/problem.md",
        "a/practice/b.md",
    )

    private val notPracticePaths = listOf(
        // 최상위 practice/ — 기존 글(글 저장소에 있는 꼴 그대로)
        "practice/index.md",
        "practice/programmers/그래프/01-가장 먼 노드/1-question.md",
        "practice/programmers/그래프/01-가장 먼 노드/3-answer.md",
        "practice/programmers/그래프/01-가장 먼 노드/metadata.md",
        "practice/programmers/해시/README.md",
        // 이름이 practice 인 파일 · 비슷한 이름의 폴더
        "cs/algorithm/06-binary-search/practice.md",
        "cs/algorithm/06-binary-search/practice.json",
        "cs/algorithm/06-binary-search/practices/01-x/problem.md",
        "cs/algorithm/06-binary-search/my-practice/01-x/problem.md",
        "cs/algorithm/06-binary-search/practice-notes/a.md",
        "cs/algorithm/06-binary-search/Practice/01-x/problem.md",
        "cs/algorithm/06-binary-search/PRACTICE/01-x/problem.md",
        // 보통의 글
        "cs/algorithm/06-binary-search/1-question.md",
        "cs/algorithm/06-binary-search/2-summary.md",
        "index.md",
        "practice.md",
    )

    @Test
    fun `주제 폴더 안의 practice 폴더 아래는 무엇이든 실습 경로다`() {
        val missed = practicePaths.filterNot { path -> PathGuard.isPracticePath(path) }
        assertEquals(emptyList<String>(), missed, "실습 경로로 보지 않았다")
    }

    @Test
    fun `최상위 practice 폴더의 기존 글과 이름만 비슷한 것과 대소문자가 다른 것은 실습 경로가 아니다`() {
        val wrong = notPracticePaths.filter { path -> PathGuard.isPracticePath(path) }
        assertEquals(emptyList<String>(), wrong, "실습 경로가 아닌 것을 실습 경로로 봤다")
    }

    @Test
    fun `실습 경로는 숨김이고 최상위 practice 의 기존 글은 숨김이 아니다`() {
        assertEquals(emptyList<String>(), practicePaths.filterNot { PathGuard.isHidden(it) }, "실습 경로를 숨기지 않았다")
        val visible = notPracticePaths.filterNot { it.startsWith("docs/") || it.startsWith("templates/") }
        assertEquals(emptyList<String>(), visible.filter { PathGuard.isHidden(it) }, "숨기면 안 되는 글을 숨겼다")
    }

    @Test
    fun `기존의 숨김 판정은 그대로다`() {
        assertTrue(PathGuard.isHidden("docs/logging.md"))
        assertTrue(PathGuard.isHidden("templates/log.md"))
        assertTrue(PathGuard.isHidden("docs/practice/x.md"))
        assertFalse(PathGuard.isHidden("docs.md"))
        assertFalse(PathGuard.isHidden("cs/docs/x.md"))
    }
}
