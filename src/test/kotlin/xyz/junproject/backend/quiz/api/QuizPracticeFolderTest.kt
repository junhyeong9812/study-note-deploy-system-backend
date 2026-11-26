package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed

/**
 * 퀴즈는 주제 폴더 안에 실습 폴더가 있어도 그 주제의 질문 · 정답을 지금처럼 읽는다(명세 ⑭ §11 P3a) — 실습 폴더의 파일을 질문 · 정답으로 읽지 않는다.
 * 실습 폴더를 주제로 부르면(그 안에 질문 · 정답 꼴의 파일을 둔 경우까지) 주제의 꼴이 틀린 요청으로 422 `invalid_request` 다(숨김 폴더의 주제와 같은 갈래).
 */
class QuizPracticeFolderTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    private val practiceFolder = "$TOPIC/practice/01-process-table"

    private fun addPracticeFolder() {
        bed.notes.commit(QuizContent.NEW_SHA, mapOf(
            "$practiceFolder/problem.md" to "# 프로세스 표\n\n1. PRACTICE-MARK 무엇을 만드는가?\n",
            "$practiceFolder/practice.json" to """{"language": "python", "kind": "code", "title": "프로세스 표"}""",
            "$practiceFolder/solution/README.md" to "# 모범 답안\nPRACTICE-MARK\n",
            // 틀린 꼴이지만 — 실습 폴더 안에 질문 · 정답 이름의 파일이 있어도 주제가 되지 않는다
            "$practiceFolder/1-question.md" to "# 질문\n\n## 질문\n\n1. PRACTICE-MARK 숨은 질문\n",
            "$practiceFolder/3-answer.md" to "# 정답\n\n## 정답\n\n### 1. PRACTICE-MARK\n\n숨은 정답\n",
        ))
    }

    @Test
    fun `실습 폴더가 생겨도 주제의 질문은 지금과 같고 실습 폴더의 파일을 읽지 않는다`() {
        addPracticeFolder()
        val response = bed.questions(TOPIC)
        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        assertEquals(QuizContent.NEW_SHA, data["content_sha"])
        @Suppress("UNCHECKED_CAST")
        val questions = data["questions"] as List<Map<String, Any?>>
        assertEquals(QuizContent.processSplit.questions.map { it.text }, questions.map { it["text"] })
        assertFalse(response.contentAsString.contains("PRACTICE-MARK"), response.contentAsString)
        assertFalse(bed.notes.everyReadPath().any { it.contains("/practice/") }, "실습 폴더의 파일을 읽었다: ${bed.notes.everyReadPath()}")
    }

    @Test
    fun `실습 폴더를 주제로 부르면 422 이고 그 안의 파일을 읽지 않는다`() {
        addPracticeFolder()
        val response = bed.questions(practiceFolder)
        assertEquals(422, response.status, response.contentAsString)
        assertTrue(response.contentAsString.contains("\"invalid_request\""), response.contentAsString)
        assertFalse(response.contentAsString.contains("PRACTICE-MARK"), response.contentAsString)
        assertFalse(bed.notes.everyReadPath().any { it.contains("/practice/") }, "실습 폴더의 파일을 읽었다: ${bed.notes.everyReadPath()}")
    }
}
