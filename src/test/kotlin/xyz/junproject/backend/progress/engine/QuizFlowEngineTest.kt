package xyz.junproject.backend.progress.engine

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.backend.progress.api.VisitorCookie
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.progress.infra.ProgressStore
import xyz.junproject.backend.quiz.QuizContent
import xyz.junproject.backend.quiz.QuizContent.TOPIC
import xyz.junproject.backend.quiz.QuizTestBed
import java.time.Duration

/**
 * 실제 PostgreSQL 위에서 퀴즈 경로 한 바퀴(명세 18 · 5): 첫 답 → 쿠키 → 질문 목록의 상태 → 채점 → 복습 목록 → 다시 보기 → 지워질 수 → 지우기.
 * 내용과 대화 이력은 가짜, 기록은 실제 저장소(`ProgressStore`). 남의 기록은 지우기 뒤에도 그대로다.
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuizFlowEngineTest {
    private val schema = "quiz_flow_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val pool = EngineSupport.schemaPool(schema)
    private val jdbc = JdbcTemplate(pool)
    private val session = "123e4567-e89b-12d3-a456-426614174000"

    @AfterAll
    fun close() {
        administrationJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        pool.close()
        administration.close()
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    @Suppress("UNCHECKED_CAST")
    private fun statusesOf(bed: QuizTestBed, response: org.springframework.mock.web.MockHttpServletResponse) =
        (bed.data(response)["questions"] as List<Map<String, Any?>>).associate { it["number"] to it["status"] as Map<String, Any?>? }

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(bed: QuizTestBed, response: org.springframework.mock.web.MockHttpServletResponse) =
        bed.data(response)["items"] as List<Map<String, Any?>>

    @Test
    fun `첫 답부터 지우기까지 실제 DB 에서 한 바퀴 돈다`() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        QuizTestBed(records = ProgressStore(jdbc)).use { bed ->
            // 다른 방문자 — 지우기 뒤에도 남아야 한다
            val othersAnswer = bed.submit(bed.answerBody(number = 3, answer = "남의 답"))
            assertEquals(true, bed.data(othersAnswer)["saved"], othersAnswer.contentAsString)

            // 1. 첫 답 → 쿠키
            val first = bed.submit(bed.answerBody(number = 1, answer = "내 첫 답"))
            assertEquals(200, first.status, first.contentAsString)
            assertEquals(true, bed.data(first)["saved"])
            assertTrue(first.contentAsString.contains("ANSWER-MARK-1"))
            val token = bed.issuedTokens.last()
            bed.assertSameCookie(VisitorCookie.issue(token), bed.setCookies(first)[VisitorCookie.NAME])
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM study_visitor WHERE token_hash = ?", Int::class.java, VisitorToken.hash(token)))
            val cookie = bed.visitorCookie(token)
            val attemptId = (bed.data(first)["attempt"] as Map<*, *>)["attempt_id"]

            // 2. 질문 목록의 상태 — 채점 안 함
            val beforeGrade = bed.questions(TOPIC, cookie)
            assertEquals("loaded", bed.data(beforeGrade)["progress"])
            QuizContent.ANSWER_MARKERS.forEach { assertFalse(beforeGrade.contentAsString.contains(it)) }
            val pending = statusesOf(bed, beforeGrade)
            assertEquals(true, pending[1]!!["awaiting_grade"])
            assertEquals(false, pending[2]!!["answered"])
            assertEquals(false, pending[3]!!["answered"], "남의 시도가 내 상태에 섞였다")

            // 3. 채점
            val graded = bed.grade(mapOf("attempt_id" to attemptId, "grade" to "CORRECT"), cookie)
            assertEquals(200, graded.status, graded.contentAsString)
            assertEquals(1, count("quiz_grade"))
            val afterGrade = statusesOf(bed, bed.questions(TOPIC, cookie))
            assertEquals(mapOf("answered" to true, "awaiting_grade" to false, "stage" to "ONE_DAY", "next_review_on" to "2026-11-26",
                "due" to false, "has_outdated_attempts" to false), afterGrade[1])

            // 4. 복습 목록 — 오늘은 없고, 다음 날 1번이 나온다
            assertEquals(emptyList<Any>(), itemsOf(bed, bed.review(cookie)))
            bed.clock.advance(Duration.ofDays(1))
            assertEquals(listOf(TOPIC to 1), itemsOf(bed, bed.review(cookie)).map { it["topic"] to it["number"] })

            // 5. 다시 보기
            val revisit = bed.revisit(TOPIC, "1", cookie)
            assertEquals(200, revisit.status, revisit.contentAsString)
            assertEquals(listOf("내 첫 답"), (bed.data(revisit)["attempts"] as List<*>).map { (it as Map<*, *>)["answer"] })
            assertEquals(404, bed.revisit(TOPIC, "3", cookie).status, "남의 답을 낸 질문의 정답이 나왔다")

            // 6. 지워질 수 → 지우기
            bed.chatHistory.sessionsWithHistory += session
            val chatCookie = Cookie(QuizTestBed.CHAT_COOKIE, session)
            assertEquals(mapOf("quiz_attempts" to 1, "chat_history" to true), bed.data(bed.myRecords(cookie, chatCookie)))
            val deleted = bed.deleteMyRecords(cookie, chatCookie)
            assertEquals(200, deleted.status, deleted.contentAsString)
            assertEquals(mapOf("quiz_records" to "deleted", "deleted_attempts" to 1, "chat_history" to "deleted"), bed.data(deleted))
            bed.assertSameCookie(VisitorCookie.expire(), bed.setCookies(deleted)[VisitorCookie.NAME])
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM study_visitor WHERE token_hash = ?", Int::class.java, VisitorToken.hash(token)))

            // 남의 기록은 그대로 · 내 채점도 함께 지워짐
            assertEquals(1, count("study_visitor"))
            assertEquals(1, count("quiz_attempt"))
            assertEquals("남의 답", jdbc.queryForObject("SELECT answer FROM quiz_attempt", String::class.java))
            assertEquals(0, count("quiz_grade"))

            // 지운 뒤의 옛 쿠키는 모르는 방문자다
            assertEquals("none", bed.data(bed.questions(TOPIC, cookie))["progress"])
        }
    }
}
