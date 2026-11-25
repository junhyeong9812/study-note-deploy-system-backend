package xyz.junproject.backend.quiz.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import xyz.junproject.backend.chat.api.ChatSessionCookie
import xyz.junproject.backend.progress.api.VisitorCookie
import xyz.junproject.backend.progress.usecase.StoredAttempt
import xyz.junproject.backend.quiz.domain.QuizAnswer
import xyz.junproject.backend.quiz.usecase.AnswerRequest
import xyz.junproject.backend.quiz.usecase.PartDeletion
import xyz.junproject.backend.quiz.usecase.QuestionStatus
import xyz.junproject.backend.quiz.usecase.QuizOutcome
import xyz.junproject.backend.quiz.usecase.QuizRejection
import xyz.junproject.backend.quiz.usecase.QuizService
import xyz.junproject.backend.shared.api.Envelope
import xyz.junproject.backend.shared.infra.RequestLog

data class AnswerIn(
    val attempt_id: String? = null,
    val topic: String? = null,
    val question_number: Int? = null,
    val answer: String? = null,
    val content_sha: String? = null,
    val question_hash: String? = null,
)

data class GradeIn(val attempt_id: String? = null, val grade: String? = null)

/**
 * 퀴즈 경로 `/api/quiz/…`(명세 18.1 · 19). 저장소를 바로 부르지 않는다 — [QuizService] 로만(명세 18.2).
 * 쿠키: `study_visitor` 는 답이 저장된 응답에만 놓고(기한 늘리기 포함), 내 기록 지우기에서 지운 쪽의 쿠키만 만료시킨다.
 * 그 밖의 응답(읽기 · 거절 · 확인 불가)에는 `Set-Cookie` 가 없다. 거절 응답에는 정답도 입력의 조각도 싣지 않는다.
 */
@RestController
@RequestMapping("/api/quiz")
class QuizController(
    private val quizService: QuizService,
    private val requestLog: RequestLog,
) {
    private fun statusOf(reason: QuizRejection): HttpStatus = when (reason) {
        QuizRejection.INVALID_REQUEST, QuizRejection.ANSWER_TOO_LONG -> HttpStatus.UNPROCESSABLE_ENTITY
        QuizRejection.NOT_FOUND, QuizRejection.ATTEMPT_NOT_FOUND, QuizRejection.VISITOR_UNKNOWN, QuizRejection.NOT_ANSWERED -> HttpStatus.NOT_FOUND
        QuizRejection.QUIZ_UNAVAILABLE, QuizRejection.QUESTION_CHANGED, QuizRejection.ATTEMPT_ID_TAKEN -> HttpStatus.CONFLICT
        QuizRejection.STORAGE_UNAVAILABLE, QuizRejection.CONTENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
    }

    private fun <T> respond(outcome: QuizOutcome<T>, body: (T) -> Map<String, Any?>): ResponseEntity<Map<String, Any?>> = when (outcome) {
        is QuizOutcome.Done -> ResponseEntity.ok(Envelope.ok(body(outcome.value)))
        is QuizOutcome.Rejected -> ResponseEntity.status(statusOf(outcome.reason)).body(Envelope.fail(outcome.reason.name.lowercase()))
    }

    private fun attemptJson(attempt: StoredAttempt): Map<String, Any?> = mapOf(
        "attempt_id" to attempt.attemptId.toString(), "topic" to attempt.topicPath, "question_number" to attempt.questionNumber,
        "submitted_at" to attempt.submittedAt.toString(), "grade" to attempt.grade?.name, "graded_at" to attempt.gradedAt?.toString(),
    )

    /** 다시 보기의 내 답 한 줄 — 쓴 답의 본문은 이 경로에서만 나간다 */
    private fun myAnswerJson(attempt: StoredAttempt): Map<String, Any?> = mapOf(
        "attempt_id" to attempt.attemptId.toString(), "answer" to attempt.answer, "submitted_at" to attempt.submittedAt.toString(),
        "grade" to attempt.grade?.name, "graded_at" to attempt.gradedAt?.toString(),
    )

    private fun correctAnswerJson(answer: QuizAnswer?): Map<String, Any?>? =
        answer?.let { mapOf("number" to it.number, "title" to it.title, "text" to it.text) }

    private fun questionStatusJson(status: QuestionStatus?): Map<String, Any?>? = status?.let {
        mapOf(
            "answered" to it.answered, "awaiting_grade" to it.progress.awaitingGrade, "stage" to it.progress.stage?.name,
            "next_review_on" to it.progress.nextReviewOn?.toString(), "due" to it.due, "has_outdated_attempts" to it.hasOutdatedAttempts,
        )
    }

    @GetMapping("/questions")
    fun questions(
        @RequestParam(required = false) topic: String?,
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        return respond(quizService.questions(requestId, visitorToken, topic)) { list ->
            mapOf(
                "topic" to list.topic, "content_sha" to list.contentSha,
                "quiz_available" to (list.unsplittable == null),
                "unavailable_reason" to list.unsplittable?.reason?.name,
                "unavailable_numbers" to (list.unsplittable?.numbers ?: emptyList()),
                "progress" to list.progress.name.lowercase(),
                "questions" to list.questions.map { (question, status) ->
                    mapOf("number" to question.number, "group" to question.group, "text" to question.text, "hash" to question.hash,
                        "status" to questionStatusJson(status))
                },
            )
        }
    }

    @PostMapping("/answers")
    fun submitAnswer(
        @RequestBody body: AnswerIn,
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val request = AnswerRequest(body.attempt_id, body.topic, body.question_number, body.answer, body.content_sha, body.question_hash)
        val outcome = quizService.submitAnswer(requestId, visitorToken, request)
        val response = respond(outcome) { result ->
            mapOf(
                "saved" to (result.attempt != null),
                "not_saved_reason" to result.notSavedReason?.name?.lowercase(),
                "attempt" to result.attempt?.let(::attemptJson),
                "correct_answer" to correctAnswerJson(result.correctAnswer),
                "correct_answer_unavailable" to result.correctAnswerUnavailable,
            )
        }
        // 저장됐을 때만 쿠키를 놓는다(새 방문자는 발급, 아는 방문자는 기한 늘리기) — 확인 불가 · 상한 · 거절에는 놓지도 지우지도 않는다
        val cookieToken = (outcome as? QuizOutcome.Done)?.value?.cookieToken ?: return response
        return ResponseEntity.status(response.statusCode).header(HttpHeaders.SET_COOKIE, VisitorCookie.issue(cookieToken).toString()).body(response.body)
    }

    @PostMapping("/grades")
    fun grade(
        @RequestBody body: GradeIn,
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        return respond(quizService.grade(requestId, visitorToken, body.attempt_id, body.grade)) { attempt -> mapOf("attempt" to attemptJson(attempt)) }
    }

    @GetMapping("/answers")
    fun revisit(
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) number: String?,
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        return respond(quizService.revisit(requestId, visitorToken, topic, number)) { revisit ->
            mapOf(
                "topic" to revisit.topic, "number" to revisit.number,
                "correct_answer" to correctAnswerJson(revisit.correctAnswer),
                "attempts" to revisit.attempts.map(::myAnswerJson),
                "outdated_attempts" to revisit.outdatedAttempts.map(::myAnswerJson),
            )
        }
    }

    @GetMapping("/review")
    fun review(
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        return respond(quizService.review(requestId, visitorToken)) { review -> mapOf(
            "progress" to review.progress.name.lowercase(),
            "items" to review.items.map { item ->
                mapOf(
                    "topic" to item.topic, "number" to item.question.number, "group" to item.question.group,
                    "preview" to item.question.text.take(QuizService.REVIEW_PREVIEW_CHARS),
                    "stage" to item.progress.stage?.name, "next_review_on" to item.progress.nextReviewOn?.toString(),
                )
            },
            "truncated" to review.truncated,
            "missing_topics" to review.missingTopics,
            "unsplittable_topics" to review.unsplittableTopics,
            "missing_questions" to review.missingQuestions,
        ) }
    }

    @GetMapping("/my-records")
    fun myRecords(
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @CookieValue(ChatSessionCookie.NAME, required = false) chatSession: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val counts = quizService.recordCounts(requestId, visitorToken, chatSession)
        return ResponseEntity.ok(Envelope.ok(mapOf("quiz_attempts" to counts.quizAttempts, "chat_history" to counts.chatHistory)))
    }

    /** 하나라도 실패하면 503 + 무엇이 지워졌고 무엇이 남았는지. 지운 쪽의 쿠키만 만료시킨다 */
    @DeleteMapping("/my-records")
    fun deleteMyRecords(
        @CookieValue(VisitorCookie.NAME, required = false) visitorToken: String?,
        @CookieValue(ChatSessionCookie.NAME, required = false) chatSession: String?,
        @RequestHeader("X-Request-Id", required = false) incomingId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.acceptOrIssue(incomingId)
        val deletion = quizService.deleteMyRecords(requestId, visitorToken, chatSession)
        val data = mapOf(
            "quiz_records" to deletion.quiz.name.lowercase(),
            "deleted_attempts" to deletion.deletedAttempts,
            "chat_history" to deletion.chatHistory.name.lowercase(),
        )
        val expired = buildList<ResponseCookie> {
            if (deletion.quiz == PartDeletion.DELETED) add(VisitorCookie.expire())
            if (deletion.chatHistory == PartDeletion.DELETED) add(ChatSessionCookie.expire())
        }
        val body = if (deletion.complete) Envelope.ok(data) else Envelope.fail("not_fully_deleted") + ("data" to data)
        val status = if (deletion.complete) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        val builder = ResponseEntity.status(status)
        expired.forEach { cookie -> builder.header(HttpHeaders.SET_COOKIE, cookie.toString()) }
        return builder.body(body)
    }
}
