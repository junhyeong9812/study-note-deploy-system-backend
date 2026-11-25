package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.BROKEN_TOPIC
import xyz.junproject.backend.quiz.QuizContent.OTHER_TOPIC
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import xyz.junproject.backend.quiz.usecase.QuizService
import java.time.Instant

/**
 * 명세 23 — 쪼갠 결과를 (판의 SHA, 주제)로 기억한다(판으로 읽은 글은 바뀌지 않는다). "없는 주제"도 그 판에 한해 기억한다.
 * 읽기에 실패한 것(장애)은 기억하지 않는다. 상한을 넘으면 오래 안 쓴 것부터 버린다. 기억이 응답을 바꾸지 않는다 — 방문자의 상태는 기억에 섞이지 않는다.
 * 읽은 횟수는 가짜 내용 저장소의 판을 지정한 읽기 기록(readsAt)으로 센다.
 */
class QuizContentCacheTest {
    private val bed = QuizTestBed()

    @AfterEach
    fun close() = bed.close()

    /** 그 주제의 파일을 판을 지정해 읽은 횟수 */
    private fun QuizTestBed.readsOf(topic: String, sha: String? = null) =
        notes.readsAt.count { (readSha, path) -> path.startsWith("$topic/") && (sha == null || readSha == sha) }

    @Test
    fun `같은 판 같은 주제의 질문 목록을 두 번 물어도 git 읽기는 한 번이다`() {
        val first = bed.questions(TOPIC)
        val second = bed.questions(TOPIC)
        assertEquals(200, second.status, second.contentAsString)
        assertEquals(2, bed.readsOf(TOPIC), "질문 · 정답 파일 한 번씩이어야 한다: ${bed.notes.readsAt}")
        assertEquals(first.contentAsString, second.contentAsString, "기억이 응답을 바꿨다")
    }

    @Test
    fun `질문 목록 뒤의 답 내기는 기억한 것을 써서 다시 읽지 않는다`() {
        bed.questions(TOPIC)
        val response = bed.submit(bed.answerBody(number = 2))
        assertEquals(true, bed.data(response)["saved"], response.contentAsString)
        assertTrue(response.contentAsString.contains("ANSWER-MARK-2"))
        assertEquals(2, bed.readsOf(TOPIC), bed.notes.readsAt.toString())
    }

    @Test
    fun `복습 목록을 두 번 물으면 둘째는 git 을 읽지 않고 응답이 같다`() {
        val (token, visitor) = bed.knownVisitor()
        bed.putAttempt(visitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        bed.putAttempt(visitor, OTHER_TOPIC, 1, Instant.parse("2026-11-20T01:00:00Z"), Grade.WRONG)
        bed.putAttempt(visitor, BROKEN_TOPIC, 1, Instant.parse("2026-11-20T01:00:00Z"), Grade.WRONG, questionHash = QuizContent.UNKNOWN_HASH)

        val first = bed.review(bed.visitorCookie(token))
        val readsAfterFirst = bed.notes.readsAt.size
        val second = bed.review(bed.visitorCookie(token))

        assertTrue(readsAfterFirst > 0)
        assertEquals(readsAfterFirst, bed.notes.readsAt.size, "둘째 복습 목록이 git 을 다시 읽었다: ${bed.notes.readsAt.drop(readsAfterFirst)}")
        assertEquals(first.contentAsString, second.contentAsString)
        assertEquals(2, (bed.data(second)["items"] as List<*>).size, second.contentAsString)
    }

    @Test
    fun `판이 바뀌면 새 판은 다시 읽는다`() {
        bed.questions(TOPIC)
        bed.notes.commit(QuizContent.NEW_SHA, mapOf("$TOPIC/1-question.md" to QuizContent.OLD_PROCESS_QUESTIONS,
            "$TOPIC/3-answer.md" to QuizContent.OLD_PROCESS_ANSWERS))

        val response = bed.questions(TOPIC)

        assertEquals(QuizContent.NEW_SHA, bed.data(response)["content_sha"])
        assertEquals(2, bed.readsOf(TOPIC, QuizContent.NEW_SHA), bed.notes.readsAt.toString())
        assertTrue(response.contentAsString.contains("(옛 판)"), "새 판의 글이 아니다: ${response.contentAsString}")
    }

    @Test
    fun `다른 주제는 따로 기억한다`() {
        bed.questions(TOPIC)
        val other = bed.questions(OTHER_TOPIC)
        assertEquals(200, other.status)
        assertEquals(2, bed.readsOf(OTHER_TOPIC), bed.notes.readsAt.toString())
        assertTrue(other.contentAsString.contains("QUESTION-MARK-TCP-1"), other.contentAsString)
        assertFalse(other.contentAsString.contains("QUESTION-MARK-1 "), "다른 주제의 기억이 섞였다")
    }

    @Test
    fun `없는 주제도 그 판에 한해 기억하고 다음 판에 생기면 보인다`() {
        val later = "cs/os/later-topic"
        assertEquals(404, bed.questions(later).status)
        val readsAfterFirst = bed.readsOf(later)
        assertEquals(404, bed.questions(later).status)
        assertTrue(readsAfterFirst > 0)
        assertEquals(readsAfterFirst, bed.readsOf(later), "없는 주제를 다시 읽었다: ${bed.notes.readsAt}")

        bed.notes.commit(QuizContent.NEW_SHA, mapOf("$later/1-question.md" to QuizContent.TCP_QUESTIONS, "$later/3-answer.md" to QuizContent.TCP_ANSWERS))
        val appeared = bed.questions(later)
        assertEquals(200, appeared.status, appeared.contentAsString)
        assertEquals(true, bed.data(appeared)["quiz_available"])
    }

    @Test
    fun `읽기에 실패한 것은 기억하지 않아 회복되면 다음 요청은 정상이다`() {
        bed.notes.failingPaths += "$TOPIC/1-question.md"
        assertEquals(503, bed.questions(TOPIC).status)
        bed.notes.failingPaths.clear()
        val recovered = bed.questions(TOPIC)
        assertEquals(200, recovered.status, recovered.contentAsString)
        assertEquals(3, (bed.data(recovered)["questions"] as List<*>).size)

        bed.notes.commit(QuizContent.NEW_SHA, emptyMap())
        bed.notes.failingPaths += "$TOPIC/3-answer.md"
        assertEquals(503, bed.questions(TOPIC).status, "정답 파일의 장애")
        bed.notes.failingPaths.clear()
        val answerRecovered = bed.questions(TOPIC)
        assertEquals(200, answerRecovered.status)
        assertEquals(true, bed.data(answerRecovered)["quiz_available"], "정답 파일의 장애가 '정답 모자람'으로 기억됐다: ${answerRecovered.contentAsString}")
    }

    @Test
    fun `상한을 넘으면 오래 안 쓴 것부터 버리고 버린 것은 다시 읽는다`() {
        QuizTestBed(contentCacheSize = 2).use { small ->
            small.questions(TOPIC)          // 기억: TOPIC
            small.questions(OTHER_TOPIC)    // 기억: TOPIC, OTHER
            small.questions(TOPIC)          // TOPIC 을 최근에 씀
            small.questions(BROKEN_TOPIC)   // 상한 2 — 오래 안 쓴 OTHER 를 버린다
            small.questions(TOPIC)          // 기억에 있다
            small.questions(OTHER_TOPIC)    // 버렸으니 다시 읽는다

            assertEquals(2, small.readsOf(TOPIC), "최근에 쓴 것을 버렸거나 기억하지 않았다: ${small.notes.readsAt}")
            assertEquals(4, small.readsOf(OTHER_TOPIC), "버린 것을 다시 읽지 않았다(상한을 무시함): ${small.notes.readsAt}")
            assertEquals(2, small.readsOf(BROKEN_TOPIC))
        }
    }

    @Test
    fun `기본 상한은 2000 이다`() {
        assertEquals(2_000, QuizService.CONTENT_CACHE_SIZE)
    }

    @Test
    fun `기억은 방문자의 상태를 섞지 않는다 - 두 방문자가 같은 주제를 물어도 각자의 상태다`() {
        val (firstToken, firstVisitor) = bed.knownVisitor()
        bed.putAttempt(firstVisitor, TOPIC, 1, Instant.parse("2026-11-24T01:00:00Z"), Grade.CORRECT)
        val (secondToken, _) = bed.knownVisitor()

        val firstBefore = bed.questions(TOPIC, bed.visitorCookie(firstToken))
        val second = bed.questions(TOPIC, bed.visitorCookie(secondToken))
        val anonymous = bed.questions(TOPIC)
        val firstAfter = bed.questions(TOPIC, bed.visitorCookie(firstToken))

        @Suppress("UNCHECKED_CAST")
        fun statuses(response: org.springframework.mock.web.MockHttpServletResponse) =
            (bed.data(response)["questions"] as List<Map<String, Any?>>).map { (it["status"] as Map<*, *>?)?.get("answered") }
        assertEquals(listOf(true, false, false), statuses(firstBefore))
        assertEquals(listOf(false, false, false), statuses(second), "남의 상태가 섞였다")
        assertEquals(listOf(null, null, null), statuses(anonymous))
        assertEquals(firstBefore.contentAsString, firstAfter.contentAsString)
        assertEquals(2, bed.readsOf(TOPIC))
    }
}
