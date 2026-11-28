package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import java.time.Instant
import java.util.UUID

/**
 * 명세 22 — 퀴즈 경로는 질문 · 정답 파일을 늘 판(SHA)을 지정해 git 에서 읽는다(`readFileAt`). 작업 트리(`readFile`)는 읽지 않는다
 * (작업 트리는 "어느 판의 글인지"를 보장하지 않는다 — 동기화는 파일을 먼저 바꾸고 지금 판의 표시를 나중에 옮긴다).
 * 경로마다: 작업 트리 읽기 기록이 비어 있고, 판을 지정한 읽기의 SHA 가 기대한 판이다.
 */
class QuizReadsByVersionTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    private fun assertReadOnlyAt(sha: String, vararg expectedPaths: String) {
        assertEquals(emptyList<String>(), bed.notes.reads, "퀴즈 경로가 작업 트리를 읽었다")
        assertTrue(bed.notes.readsAt.isNotEmpty(), "판을 지정해 읽지 않았다")
        assertTrue(bed.notes.readsAt.all { (readSha, _) -> readSha == sha }, "다른 판을 읽었다: ${bed.notes.readsAt}")
        expectedPaths.forEach { path -> assertTrue((sha to path) in bed.notes.readsAt, "$path 를 $sha 로 읽지 않았다: ${bed.notes.readsAt}") }
    }

    @Test
    fun `질문 목록은 작업 트리를 읽지 않고 지금 판의 SHA 로 읽는다`() {
        val response = bed.questions(TOPIC)
        assertEquals(200, response.status, response.contentAsString)
        assertReadOnlyAt(QuizContent.HEAD, "$TOPIC/1-question.md", "$TOPIC/3-answer.md")
    }

    @Test
    fun `지금 판이 옮겨 가면 질문 목록은 새 판의 SHA 로 읽고 그 SHA 를 낸다`() {
        bed.notes.commit(QuizContent.NEW_SHA, mapOf("$TOPIC/1-question.md" to QuizContent.OLD_PROCESS_QUESTIONS))
        val response = bed.questions(TOPIC)
        assertEquals(QuizContent.NEW_SHA, bed.data(response)["content_sha"])
        assertReadOnlyAt(QuizContent.NEW_SHA, "$TOPIC/1-question.md")
    }

    @Test
    fun `답을 낸 질문의 재전송은 작업 트리를 읽지 않고 저장된 판의 SHA 로 읽는다`() {
        val (token, _) = bed.knownVisitor()
        val body = bed.answerBody(number = 2, attemptId = UUID.randomUUID().toString())
        bed.submit(body, bed.visitorCookie(token))
        bed.notes.reads.clear()
        bed.notes.readsAt.clear()

        val again = bed.submit(body.plus("question_number" to 1).plus("question_hash" to QuizContent.hashOf(TOPIC, 1)), bed.visitorCookie(token))

        assertEquals(200, again.status, again.contentAsString)
        assertEquals(2, (bed.data(again)["correct_answer"] as Map<*, *>)["number"])
        assertEquals(emptyList<String>(), bed.notes.reads, "퀴즈 경로가 작업 트리를 읽었다")
        // 처음 판은 첫 답에서 이미 읽어 기억했다(명세 23) — 다시 읽는다면 그 판으로만
        assertTrue(bed.notes.readsAt.all { (sha, _) -> sha == QuizContent.HEAD }, bed.notes.readsAt.toString())
    }

    @Test
    fun `복습 목록은 작업 트리를 읽지 않고 지금 판의 SHA 로 읽는다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        val response = bed.review(bed.visitorCookie(token))
        assertEquals(1, (bed.data(response)["items"] as List<*>).size, response.contentAsString)
        assertReadOnlyAt(QuizContent.HEAD, "$TOPIC/1-question.md")
    }

    @Test
    fun `다시 보기는 작업 트리를 읽지 않고 지금 판의 SHA 로 읽는다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 2, QuizTestBed.NOW)
        val response = bed.revisit(TOPIC, "2", bed.visitorCookie(token))
        assertEquals(200, response.status, response.contentAsString)
        assertReadOnlyAt(QuizContent.HEAD, "$TOPIC/1-question.md", "$TOPIC/3-answer.md")
    }

    @Test
    fun `질문 목록은 지금 판을 한 번만 읽는다 - 읽은 직후 새 판이 커밋돼도 응답의 SHA 와 글 해시가 같은 판의 것이다`() {
        bed.notes.afterCurrentHead = {
            bed.notes.commit(QuizContent.NEW_SHA, mapOf("$TOPIC/1-question.md" to QuizContent.OLD_PROCESS_QUESTIONS,
                "$TOPIC/3-answer.md" to QuizContent.OLD_PROCESS_ANSWERS))
        }

        val response = bed.questions(TOPIC)

        assertEquals(200, response.status, response.contentAsString)
        val data = bed.data(response)
        val sha = data["content_sha"] as String
        val files = bed.notes.filesAt(sha)
        val split = xyz.junproject.backend.quiz.domain.QuizSplitter.split(files.getValue("$TOPIC/1-question.md"), files.getValue("$TOPIC/3-answer.md"))
            as xyz.junproject.backend.quiz.domain.QuizSplitResult.Split
        @Suppress("UNCHECKED_CAST")
        val questions = data["questions"] as List<Map<String, Any?>>
        assertEquals(split.questions.map { it.hash }, questions.map { it["hash"] }, "응답의 SHA($sha) 판과 글 · 해시가 다른 판의 것이다")
        assertEquals(QuizContent.HEAD, sha, "지금 판을 두 번 읽었다")
    }
}
