package xyz.junproject.backend.quiz.usecase

import org.springframework.dao.DataAccessException
import xyz.junproject.backend.chat.usecase.ChatHistoryPort
import xyz.junproject.backend.chat.usecase.ChatService
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.domain.QuestionProgress
import xyz.junproject.backend.progress.domain.ReviewSchedule
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.AttemptSummary
import xyz.junproject.backend.progress.usecase.CountOutcome
import xyz.junproject.backend.progress.usecase.DeleteOutcome
import xyz.junproject.backend.progress.usecase.GradeOutcome
import xyz.junproject.backend.progress.usecase.StoredAttempt
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.progress.usecase.SubmitOutcome
import xyz.junproject.backend.progress.usecase.VisitorRead
import xyz.junproject.backend.quiz.domain.QuizAnswer
import xyz.junproject.backend.quiz.domain.QuizQuestion
import xyz.junproject.backend.quiz.domain.QuizSplitResult
import xyz.junproject.backend.quiz.domain.QuizSplitter
import xyz.junproject.backend.shared.infra.RequestLog
import java.io.FileNotFoundException
import java.time.Clock
import java.util.UUID

/** 퀴즈 경로가 거절하는 까닭 — 컨트롤러가 응답의 상태와 code(이름의 소문자)로 옮긴다. 어느 거절에도 정답을 싣지 않는다 */
enum class QuizRejection {
    INVALID_REQUEST, ANSWER_TOO_LONG, NOT_FOUND, QUIZ_UNAVAILABLE, QUESTION_CHANGED,
    ATTEMPT_ID_TAKEN, ATTEMPT_NOT_FOUND, VISITOR_UNKNOWN, NOT_ANSWERED, STORAGE_UNAVAILABLE,
    /** 내용 저장소(git)를 읽지 못함 — 없음이 아니라 장애(명세 23) */
    CONTENT_UNAVAILABLE,
}

sealed interface QuizOutcome<out T> {
    data class Done<T>(val value: T) : QuizOutcome<T>
    data class Rejected(val reason: QuizRejection) : QuizOutcome<Nothing>
}

/** 이 방문자의 기록을 불러왔나 — 없음(쿠키 없음 · 모르는 토큰) / 불러옴 / 불러올 수 없음(저장소 장애) */
enum class ProgressLoad { NONE, LOADED, UNAVAILABLE }

/** 질문 하나의 지금 상태 — 지금 질문(번호와 해시가 같은 것)의 시도로만 계산한다. 옛 질문의 기록은 있는지만 */
data class QuestionStatus(val answered: Boolean, val progress: QuestionProgress, val due: Boolean, val hasOutdatedAttempts: Boolean)

data class QuestionWithStatus(val question: QuizQuestion, val status: QuestionStatus?)

/** 주제의 질문 목록 — [unsplittable] 이 있으면 퀴즈 없음(질문 없이). 정답은 싣지 않는다 */
data class QuestionList(
    val topic: String,
    val contentSha: String,
    val unsplittable: QuizSplitResult.Unsplittable?,
    val progress: ProgressLoad,
    val questions: List<QuestionWithStatus>,
)

/** 브라우저가 보낸 답 요청 — 검증 전의 값 그대로 */
data class AnswerRequest(
    val attemptId: String?,
    val topic: String?,
    val questionNumber: Int?,
    val answer: String?,
    val contentSha: String?,
    val questionHash: String?,
)

/** 저장하지 못했지만 정답은 싣는 경우 셋(명세 19) */
enum class NotSavedReason { STORAGE_UNAVAILABLE, NEW_VISITOR_RATE_LIMITED, DAILY_LIMIT_REACHED }

/**
 * 답을 낸 결과 — 저장됐으면 [attempt] 와 쿠키에 놓을 [cookieToken], 아니면 [notSavedReason]. 정답은 두 경우 모두 싣는다.
 * [correctAnswerUnavailable]: 저장된 시도의 정답을 찾다 내용 장애가 남(정답 칸이 빈 까닭이 "없음"이 아님)
 */
data class AnswerResult(
    val attempt: StoredAttempt?,
    val notSavedReason: NotSavedReason?,
    val correctAnswer: QuizAnswer?,
    val cookieToken: String?,
    val correctAnswerUnavailable: Boolean = false,
)

/** 다시 보기 — [correctAnswer] 는 지금 질문의 시도가 있을 때만. 옛 질문의 시도는 [outdatedAttempts] 로 가른다 */
data class Revisit(
    val topic: String,
    val number: Int,
    val correctAnswer: QuizAnswer?,
    val attempts: List<StoredAttempt>,
    val outdatedAttempts: List<StoredAttempt>,
)

data class ReviewItem(val topic: String, val question: QuizQuestion, val progress: QuestionProgress)

/** 오늘 복습할 것 — 계산에 넣지 못한 기록은 수로 드러낸다(없어진 주제 · 쪼갤 수 없게 된 주제 · 없어진 질문 번호) */
data class ReviewList(
    val progress: ProgressLoad,
    val items: List<ReviewItem>,
    val truncated: Boolean,
    val missingTopics: Int,
    val unsplittableTopics: Int,
    val missingQuestions: Int,
)

/** 지워질 수 — 확인하지 못한 쪽은 null(0 이나 false 로 보이지 않게) */
data class RecordCounts(val quizAttempts: Int?, val chatHistory: Boolean?)

enum class PartDeletion { NONE, DELETED, FAILED }

/** 내 기록 지우기의 결과 — 쪽마다 지웠나 · 지울 것이 없었나(쿠키 없음) · 실패했나 */
data class RecordDeletion(val quiz: PartDeletion, val deletedAttempts: Int?, val chatHistory: PartDeletion) {
    val complete: Boolean get() = quiz != PartDeletion.FAILED && chatHistory != PartDeletion.FAILED
}

/**
 * 퀴즈 경로의 일(명세 18) — 질문 목록 · 답을 냄 · 채점 · 다시 보기 · 오늘 복습할 것 · 지워질 수 · 내 기록 지우기.
 * 저장소는 [StudyProgressService] 로만 닿는다(명세 18.2). 시각은 주입한 시계, 날짜는 한국 시각.
 * 입력 검증은 내용 저장소(git) · 기록 저장소 · 로그보다 먼저다 — 거절된 주제 경로의 원문 · 쓴 답의 본문 · 정답 글은 로그에 쓰지 않는다.
 */
class QuizService(
    private val notes: NoteSourcePort,
    private val progress: StudyProgressService,
    private val chatHistory: ChatHistoryPort,
    private val clock: Clock,
    private val requestLog: RequestLog,
    /** 쿠키 없이 내는 첫 답(새 방문자 만들기)의 앱 전체 분당 상한(명세 18.2) */
    private val newVisitorsPerMinute: Int = NEW_VISITORS_PER_MINUTE,
    /** 복습 목록이 읽는 시도 줄 수의 상한(명세 18.2 — 넘으면 최근 것부터, 잘렸다고 표시) */
    private val reviewAttemptLimit: Int = REVIEW_ATTEMPT_LIMIT,
    /** 쪼갠 결과를 (판의 SHA, 주제)로 기억하는 수의 상한(명세 23 — 오래 안 쓴 것부터 버린다) */
    private val contentCacheSize: Int = CONTENT_CACHE_SIZE,
) {
    companion object {
        const val NEW_VISITORS_PER_MINUTE = 30
        const val REVIEW_ATTEMPT_LIMIT = 5_000
        /** 쪼갠 결과를 기억하는 수의 기본 상한(명세 23) */
        const val CONTENT_CACHE_SIZE = 2_000
        /** 복습 목록에 싣는 질문 글의 앞부분 — 질문 글의 앞 이만큼(String.take) */
        const val REVIEW_PREVIEW_CHARS = 80

        /** cs 아래의 주제 경로 — 마디마다 영숫자로 시작하고 영숫자 · `.` `_` `-` 만(경로 벗어남 · 빈 마디 · 줄바꿈 · 콜론 · 역슬래시가 들 수 없다) */
        private val TOPIC_PATH = Regex("cs(/[A-Za-z0-9][A-Za-z0-9._-]{0,99}){1,4}")
        private val ATTEMPT_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        /** 커밋 SHA(SHA-1 40자 · SHA-256 64자, 소문자 16진) — 이 꼴을 통과한 값만 git 의 인자로 넘긴다 */
        private val COMMIT_SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
        /** 질문 해시 — QuizSplitter.questionHash 의 꼴(소문자 16진 64자) */
        private val QUESTION_HASH = Regex("[0-9a-f]{64}")
        private val QUESTION_NUMBER = Regex("\\d{1,9}")

        fun isTopicPath(value: String): Boolean = TOPIC_PATH.matches(value)
    }

    private val newVisitorLock = Any()
    private var newVisitorMinute = Long.MIN_VALUE
    private var newVisitorsThisMinute = 0

    /**
     * 새 방문자의 칸을 하나 받는다 — 시계의 분(고정 창)마다 [newVisitorsPerMinute] 칸까지. 받으면 그 분을, 다 찼으면 null.
     * 칸을 먼저 받고 저장에 실패하면 [releaseNewVisitor] 로 돌려준다 — 동시에 몰려도 상한을 넘지 않는다.
     * 단순화: 이 앱 하나의 메모리에서 센다 — 두 자리(blue · green)가 함께 받으면 앱 전체로는 분당 상한의 2배까지, 창의 경계 앞뒤로
     * 몰리면 1분 안에 다시 2배까지 받는다. 정확히 막아야 하면 DB(또는 Redis)의 한 곳에서 센다.
     */
    private fun reserveNewVisitor(): Long? = synchronized(newVisitorLock) {
        val minute = Math.floorDiv(clock.instant().epochSecond, 60L)
        if (minute != newVisitorMinute) {
            newVisitorMinute = minute
            newVisitorsThisMinute = 0
        }
        if (newVisitorsThisMinute >= newVisitorsPerMinute) return null
        newVisitorsThisMinute += 1
        minute
    }

    /** 받은 칸을 돌려준다 — 그 칸을 받은 분이 지금도 세는 분일 때만(다음 분의 칸을 건드리지 않는다) */
    private fun releaseNewVisitor(reservedMinute: Long) = synchronized(newVisitorLock) {
        if (reservedMinute == newVisitorMinute && newVisitorsThisMinute > 0) newVisitorsThisMinute -= 1
    }

    private fun today() = ReviewSchedule.dateOf(clock.instant())

    /** 거절을 남긴다 — [why] 에는 검증을 통과한 값만 넣는다 */
    private fun rejected(requestId: String, action: String, reason: QuizRejection, why: String): QuizOutcome.Rejected {
        requestLog.log(requestId, "quiz $action rejected(${reason.name.lowercase()}) $why", "warning")
        return QuizOutcome.Rejected(reason)
    }

    // ── 내용 읽기 ──────────────────────────────────────────

    /** 내용 저장소를 읽지 못함(git 의 장애 · 시간 초과) — "없음"과 다르다. 퀴즈 경로는 503 content_unavailable 로 답한다 */
    private class ContentUnavailable(cause: Exception) : RuntimeException(cause)

    /** 기억한 쪼갠 결과 — [content] 가 null 이면 그 판에 그 주제가 없다 */
    private class RememberedTopic(val content: QuizSplitResult?)

    /**
     * (판의 SHA, 주제) → 쪼갠 결과. 판으로 읽은 글은 바뀌지 않아 기억해도 틀릴 수 없다. 상한을 넘으면 오래 안 쓴 것부터 버린다(접근 순서).
     * 넣고 꺼내기만 잠금 안에서 한다 — git 을 기다리는 동안 잠금을 쥐지 않는다. 같은 키를 둘이 함께 읽으면 둘 다 읽는다(결과는 같다).
     */
    private val rememberedTopics = object : LinkedHashMap<Pair<String, String>, RememberedTopic>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, RememberedTopic>?): Boolean = size > contentCacheSize
    }

    private fun questionFile(topic: String) = "$topic/1-question.md"
    private fun answerFile(topic: String) = "$topic/3-answer.md"

    /** 판을 지정한 읽기 — 그 판에 그 파일이 없으면 null, 그 밖의 실패는 [ContentUnavailable] */
    private fun readAt(sha: String, path: String): String? =
        try {
            notes.readFileAt(sha, path)
        } catch (_: FileNotFoundException) {
            null
        } catch (error: Exception) {
            throw ContentUnavailable(error)
        }

    /** 지금 판의 SHA — 한 요청에서 한 번 읽어 그 값으로 읽는다(두 번 읽으면 SHA 와 글이 갈릴 수 있다). 실패는 [ContentUnavailable] */
    private fun currentHead(): String =
        try {
            notes.currentHead().trim()
        } catch (error: Exception) {
            throw ContentUnavailable(error)
        }

    /**
     * [sha] 판의 그 주제(git) — 그 판을 읽을 수 없으면(얕은 사본 · 없는 SHA · 그때 없던 주제) null. 정답 파일이 없으면 정답이 모자란 주제로 쪼개진다.
     * 퀴즈 경로는 내용을 늘 판을 지정해 읽는다 — 작업 트리는 어느 판의 글인지 보장하지 않는다(동기화는 파일을 먼저 바꾸고 지금 판의 표시를
     * 나중에 옮긴다, 명세 22). [sha] 는 [COMMIT_SHA] 를 통과한 값이거나 지금 판의 SHA.
     * 두 파일을 다 읽은 뒤에만 기억한다 — 장애([ContentUnavailable])는 기억하지 않는다
     */
    private fun topicAt(sha: String, topic: String): QuizSplitResult? {
        val key = sha to topic
        synchronized(rememberedTopics) { rememberedTopics[key] }?.let { remembered -> return remembered.content }
        val questions = readAt(sha, questionFile(topic))
        val content = questions?.let { QuizSplitter.split(it, readAt(sha, answerFile(topic)) ?: "") }
        synchronized(rememberedTopics) { rememberedTopics[key] = RememberedTopic(content) }
        return content
    }

    /** 내용 장애의 로그 — 검증을 통과한 [subject] 와 예외의 종류만(답 본문 · 토큰 · 정답 글을 쓰지 않는다) */
    private fun contentFailure(requestId: String, action: String, subject: String, failure: ContentUnavailable): QuizOutcome.Rejected {
        logContentFailure(requestId, action, subject, failure)
        return QuizOutcome.Rejected(QuizRejection.CONTENT_UNAVAILABLE)
    }

    private fun logContentFailure(requestId: String, action: String, subject: String, failure: ContentUnavailable) =
        requestLog.log(requestId, "quiz $action content unavailable $subject: ${failure.cause?.javaClass?.simpleName}", "error")

    /**
     * [sha] 판의 그 주제와 실제로 읽은 판의 SHA — 그 판을 읽을 수 없으면 지금 판의 SHA 를 얻어 그 SHA 로 읽는다
     * (지금 판이 [sha] 와 같으면 같은 판을 두 번 읽지 않는다). 어느 판에도 질문 파일이 없으면 null(없는 주제)
     */
    private fun topicForSha(sha: String, topic: String): Pair<String, QuizSplitResult>? {
        topicAt(sha, topic)?.let { return sha to it }
        val head = currentHead()
        if (head == sha) return null
        return topicAt(head, topic)?.let { head to it }
    }

    /**
     * 저장된 시도의 정답 — 그 시도의 판에서(읽을 수 없으면 지금 판에서) 같은 번호 · 같은 해시의 질문일 때만. 아니면 null.
     * 같은 시도 번호로 다시 보낸 경우(다른 질문이어도) 처음 저장된 시도의 정답을 싣는 데 쓴다.
     */
    private fun answerOf(attempt: StoredAttempt): QuizAnswer? {
        val content = topicForSha(attempt.contentSha, attempt.topicPath)?.second
        val split = content as? QuizSplitResult.Split ?: return null
        val question = split.questions.firstOrNull { it.number == attempt.questionNumber } ?: return null
        // 저장된 판을 못 읽어 지금 판으로 왔을 때 질문이 바뀌었으면 여기서 멈춘다 — 빼면 재전송에 답하지 않은 새 질문의 정답이 나간다
        return if (question.hash == attempt.questionHash) split.answerOf(attempt.questionNumber) else null
    }

    /** 저장된 시도의 정답과 "찾다 장애가 남" — 장애면 ERROR 로그를 남기고 정답 칸을 비운다(저장된 것은 그대로 돌려준다) */
    private fun storedAnswerOf(requestId: String, attempt: StoredAttempt): Pair<QuizAnswer?, Boolean> =
        try {
            answerOf(attempt) to false
        } catch (failure: ContentUnavailable) {
            logContentFailure(requestId, "answer", "topic=${attempt.topicPath} question=${attempt.questionNumber}", failure)
            null to true
        }

    // ── 질문 목록 ──────────────────────────────────────────

    /** 지금 질문(같은 번호 · 같은 해시)의 시도로만 상태를 계산한다 — 해시만으로 짝짓지 않는다(번호가 바뀐 같은 글은 옛 기록) */
    private fun statusOf(question: QuizQuestion, topicAttempts: List<AttemptSummary>): QuestionStatus {
        val (current, outdated) = topicAttempts.filter { it.questionNumber == question.number }.partition { it.questionHash == question.hash }
        val questionProgress = ReviewSchedule.progressOf(current.map { it.reviewed() })
        return QuestionStatus(current.isNotEmpty(), questionProgress, questionProgress.isDueOn(today()), outdated.isNotEmpty())
    }

    /** 질문 목록 — 정답은 싣지 않는다. 쿠키가 있으면 그 주제의 시도만 읽어 상태를 붙인다(방문자를 만들지 않는다) */
    fun questions(requestId: String, presentedToken: String?, topicValue: String?): QuizOutcome<QuestionList> {
        val topic = topicValue?.takeIf(::isTopicPath) ?: return rejected(requestId, "questions", QuizRejection.INVALID_REQUEST, "topic")
        val (contentSha, read) = try {
            currentHead().let { head -> head to topicAt(head, topic) }
        } catch (failure: ContentUnavailable) {
            return contentFailure(requestId, "questions", "topic=$topic", failure)
        }
        val content = read ?: return rejected(requestId, "questions", QuizRejection.NOT_FOUND, "topic=$topic")
        if (content is QuizSplitResult.Unsplittable) {
            requestLog.log(requestId, "quiz questions unavailable topic=$topic reason=${content.reason}")
            return QuizOutcome.Done(QuestionList(topic, contentSha, content, ProgressLoad.NONE, emptyList()))
        }
        val split = content as QuizSplitResult.Split
        val (load, statuses) = when (val read = progress.attemptsOfTopic(requestId, presentedToken, topic)) {
            is VisitorRead.Read -> ProgressLoad.LOADED to split.questions.associate { it.number to statusOf(it, read.value) }
            VisitorRead.NoVisitor -> ProgressLoad.NONE to emptyMap()
            VisitorRead.StorageUnavailable -> ProgressLoad.UNAVAILABLE to emptyMap()
        }
        requestLog.log(requestId, "quiz questions topic=$topic questions=${split.questions.size} progress=${load.name.lowercase()}")
        return QuizOutcome.Done(QuestionList(topic, contentSha, null, load, split.questions.map { QuestionWithStatus(it, statuses[it.number]) }))
    }

    // ── 답을 냄 ────────────────────────────────────────────

    /**
     * 답을 받아 저장하고 그 응답에 그 질문의 정답을 싣는다(명세 18.1 · 12).
     * 차례: 주제 꼴 → 시도 번호 · 질문 번호 · 빈 답 · NUL · 길이 → SHA · 해시의 꼴 → (쿠키가 있으면) 이미 저장된 이 방문자의 시도인가 →
     * 내용 읽기(받은 SHA 의 판, 못 읽으면 지금 판의 SHA 로) → 판 확인(다시 낸 해시와 견줌) → 그다음에야 방문자 식별과 저장.
     * 저장하는 판은 검증에 실제로 쓴 판이다(명세 20). 저장하지 못한 세 경우(장애 · 새 방문자 상한 · 하루 상한)에도 정답은 싣는다.
     */
    fun submitAnswer(requestId: String, presentedToken: String?, request: AnswerRequest): QuizOutcome<AnswerResult> {
        val action = "answer"
        val topic = request.topic?.takeIf(::isTopicPath) ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "topic")
        val attemptId = request.attemptId?.takeIf(ATTEMPT_ID::matches)?.let(UUID::fromString)
            ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "attempt id")
        val number = request.questionNumber?.takeIf { it >= 0 } ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "question number")
        val answer = request.answer
        if (answer == null || answer.isBlank()) return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "blank answer")
        // PostgreSQL 의 글 칸은 NUL 을 담지 못한다 — 저장소 장애가 아니라 잘못된 입력이다
        if (answer.contains('\u0000')) return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "NUL in answer")
        val length = answer.codePointCount(0, answer.length)
        if (length > StudyProgressService.ANSWER_MAX_CHARS) return rejected(requestId, action, QuizRejection.ANSWER_TOO_LONG, "length=$length")
        val contentSha = request.contentSha?.takeIf(COMMIT_SHA::matches) ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "content sha")
        val questionHash = request.questionHash?.takeIf(QUESTION_HASH::matches)
            ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "question hash")

        // 이미 저장된 이 방문자의 시도면(응답을 잃은 재전송) 판 확인 없이 처음 저장된 것을 돌려준다 — 그 사이 질문이 바뀌었거나
        // 그 판을 더는 읽을 수 없어도(명세 20). 쿠키가 없거나 모르는 토큰 · 남의 시도 번호 · 저장소 장애는 아래의 길로 간다
        if (presentedToken != null) {
            val own = progress.ownAttempt(requestId, presentedToken, attemptId)
            if (own is VisitorRead.Read && own.value != null) {
                val stored = own.value
                requestLog.log(requestId, "quiz answer retransmission topic=${stored.topicPath} question=${stored.questionNumber}")
                val (storedAnswer, answerUnavailable) = storedAnswerOf(requestId, stored)
                return QuizOutcome.Done(AnswerResult(stored, null, storedAnswer, presentedToken, answerUnavailable))
            }
        }

        val subject = "topic=$topic question=$number"
        val version = try {
            topicForSha(contentSha, topic)
        } catch (failure: ContentUnavailable) {
            return contentFailure(requestId, action, subject, failure)
        }
        val (versionSha, content) = version ?: return rejected(requestId, action, QuizRejection.NOT_FOUND, "topic=$topic")
        val split = content as? QuizSplitResult.Split ?: return rejected(requestId, action, QuizRejection.QUIZ_UNAVAILABLE, subject)
        val question = split.questions.firstOrNull { it.number == number }
            ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "$subject(no such question)")
        if (question.hash != questionHash) return rejected(requestId, action, QuizRejection.QUESTION_CHANGED, subject)
        val correctAnswer = checkNotNull(split.answerOf(number)) { "쪼개진 주제의 질문에 정답이 없다" }

        val submission = AnswerSubmission(attemptId, topic, number, answer, versionSha, questionHash)
        val notSaved = { reason: NotSavedReason -> QuizOutcome.Done(AnswerResult(null, reason, correctAnswer, null)) }
        var reservedMinute: Long? = null
        val admitThisVisitor = { reserveNewVisitor().also { minute -> reservedMinute = minute } != null }
        val releaseThisVisitor = {
            reservedMinute?.let { minute -> releaseNewVisitor(minute) }
            reservedMinute = null
        }
        return when (val outcome = progress.submitAnswer(requestId, presentedToken, submission, admitThisVisitor, releaseThisVisitor)) {
            is SubmitOutcome.Saved -> {
                val stored = outcome.attempt
                val sameQuestion = stored.topicPath == topic && stored.questionNumber == number &&
                    stored.contentSha == versionSha && stored.questionHash == questionHash
                // 같은 시도 번호로 다른 질문을 다시 보냈으면 처음 저장된 시도의 정답이다(시도 번호가 기준 — 명세 19)
                val (storedAnswer, answerUnavailable) = if (sameQuestion) correctAnswer to false else storedAnswerOf(requestId, stored)
                QuizOutcome.Done(AnswerResult(stored, null, storedAnswer, outcome.cookieToken, answerUnavailable))
            }
            SubmitOutcome.StorageUnavailable -> notSaved(NotSavedReason.STORAGE_UNAVAILABLE)
            SubmitOutcome.NewVisitorRateLimited -> notSaved(NotSavedReason.NEW_VISITOR_RATE_LIMITED)
            SubmitOutcome.DailyLimitReached -> notSaved(NotSavedReason.DAILY_LIMIT_REACHED)
            SubmitOutcome.AttemptIdTaken -> QuizOutcome.Rejected(QuizRejection.ATTEMPT_ID_TAKEN)
            SubmitOutcome.InvalidInput -> QuizOutcome.Rejected(QuizRejection.INVALID_REQUEST)
            SubmitOutcome.AnswerTooLong -> QuizOutcome.Rejected(QuizRejection.ANSWER_TOO_LONG)
        }
    }

    // ── 채점 ──────────────────────────────────────────────

    fun grade(requestId: String, presentedToken: String?, attemptIdValue: String?, gradeValue: String?): QuizOutcome<StoredAttempt> {
        val attemptId = attemptIdValue?.takeIf(ATTEMPT_ID::matches)?.let(UUID::fromString)
            ?: return rejected(requestId, "grade", QuizRejection.INVALID_REQUEST, "attempt id")
        val grade = Grade.entries.firstOrNull { it.name == gradeValue } ?: return rejected(requestId, "grade", QuizRejection.INVALID_REQUEST, "grade")
        return when (val outcome = progress.grade(requestId, presentedToken, attemptId, grade)) {
            is GradeOutcome.Graded -> QuizOutcome.Done(outcome.attempt)
            GradeOutcome.AttemptNotFound -> QuizOutcome.Rejected(QuizRejection.ATTEMPT_NOT_FOUND)
            GradeOutcome.VisitorUnknown -> QuizOutcome.Rejected(QuizRejection.VISITOR_UNKNOWN)
            GradeOutcome.StorageUnavailable -> QuizOutcome.Rejected(QuizRejection.STORAGE_UNAVAILABLE)
        }
    }

    // ── 다시 보기 ──────────────────────────────────────────

    /** 답을 낸 지금 질문의 정답과 내 답들 — 이 방문자의 지금 질문 시도가 없으면 정답을 내주지 않는다(명세 18.2 · 19) */
    fun revisit(requestId: String, presentedToken: String?, topicValue: String?, numberValue: String?): QuizOutcome<Revisit> {
        val action = "revisit"
        val topic = topicValue?.takeIf(::isTopicPath) ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "topic")
        val number = numberValue?.takeIf(QUESTION_NUMBER::matches)?.toInt()
            ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "question number")
        val read = try {
            topicAt(currentHead(), topic)
        } catch (failure: ContentUnavailable) {
            return contentFailure(requestId, action, "topic=$topic", failure)
        }
        val content = read ?: return rejected(requestId, action, QuizRejection.NOT_FOUND, "topic=$topic")
        val split = content as? QuizSplitResult.Split ?: return rejected(requestId, action, QuizRejection.QUIZ_UNAVAILABLE, "topic=$topic")
        val question = split.questions.firstOrNull { it.number == number }
            ?: return rejected(requestId, action, QuizRejection.INVALID_REQUEST, "topic=$topic question=$number(no such question)")
        val attempts = when (val read = progress.attemptsOfQuestion(requestId, presentedToken, topic, number)) {
            is VisitorRead.Read -> read.value
            VisitorRead.NoVisitor -> return QuizOutcome.Rejected(QuizRejection.NOT_ANSWERED)
            VisitorRead.StorageUnavailable -> return QuizOutcome.Rejected(QuizRejection.STORAGE_UNAVAILABLE)
        }
        if (attempts.isEmpty()) return QuizOutcome.Rejected(QuizRejection.NOT_ANSWERED)
        val (current, outdated) = attempts.partition { it.questionHash == question.hash }
        val correctAnswer = if (current.isNotEmpty()) split.answerOf(number) else null
        requestLog.log(requestId, "quiz revisit topic=$topic question=$number attempts=${current.size} outdated=${outdated.size}")
        return QuizOutcome.Done(Revisit(topic, number, correctAnswer, current, outdated))
    }

    // ── 오늘 복습할 것 ──────────────────────────────────────

    /**
     * 복습일이 된 지금 질문의 목록 — 최근 시도 [reviewAttemptLimit] 줄까지 읽는다(한 줄 더 읽어 잘렸는지 안다).
     * 주제마다 지금 판을 읽어 같은 번호 · 같은 해시의 시도로만 계산하고, 없어진 주제 · 쪼갤 수 없는 주제 · 없어진 질문 번호는 센다.
     */
    fun review(requestId: String, presentedToken: String?): QuizOutcome<ReviewList> {
        val attempts = when (val read = progress.recentAttempts(requestId, presentedToken, reviewAttemptLimit + 1)) {
            is VisitorRead.Read -> read.value
            VisitorRead.NoVisitor -> return QuizOutcome.Done(ReviewList(ProgressLoad.NONE, emptyList(), false, 0, 0, 0))
            VisitorRead.StorageUnavailable -> return QuizOutcome.Done(ReviewList(ProgressLoad.UNAVAILABLE, emptyList(), false, 0, 0, 0))
        }
        val truncated = attempts.size > reviewAttemptLimit
        val today = today()
        val items = mutableListOf<ReviewItem>()
        var missingTopics = 0
        var unsplittableTopics = 0
        var missingQuestions = 0
        // 장애는 "없어진 주제"로 세지 않는다 — 한 주제라도 읽지 못하면 목록 전체를 503 으로(명세 23)
        var readingTopic = "-"
        try {
            val head = currentHead()
            attempts.takeLast(reviewAttemptLimit).groupBy { it.topicPath }.forEach { (topic, topicAttempts) ->
                // 저장된 주제 경로도 꼴을 본 뒤에만 파일 경로로 쓴다. 주제마다 git 프로세스 둘(처음 한 번 — 그 뒤는 기억한 것)
                if (isTopicPath(topic)) readingTopic = topic
                when (val content = if (isTopicPath(topic)) topicAt(head, topic) else null) {
                    null -> missingTopics += 1
                    is QuizSplitResult.Unsplittable -> unsplittableTopics += 1
                    is QuizSplitResult.Split -> topicAttempts.groupBy { it.questionNumber }.forEach { (number, questionAttempts) ->
                        val question = content.questions.firstOrNull { it.number == number }
                        if (question == null) {
                            missingQuestions += 1
                        } else {
                            val questionProgress = ReviewSchedule.progressOf(questionAttempts.filter { it.questionHash == question.hash }.map { it.reviewed() })
                            if (questionProgress.isDueOn(today)) items += ReviewItem(topic, question, questionProgress)
                        }
                    }
                }
            }
        } catch (failure: ContentUnavailable) {
            return contentFailure(requestId, "review", "topic=$readingTopic", failure)
        }
        val ordered = items.sortedWith(compareBy({ it.progress.nextReviewOn }, { it.topic }, { it.question.number }))
        requestLog.log(requestId, "quiz review items=${ordered.size} truncated=$truncated missingTopics=$missingTopics " +
            "unsplittableTopics=$unsplittableTopics missingQuestions=$missingQuestions")
        return QuizOutcome.Done(ReviewList(ProgressLoad.LOADED, ordered, truncated, missingTopics, unsplittableTopics, missingQuestions))
    }

    // ── 내 기록 ────────────────────────────────────────────

    /** `chat_session` 값 — 정해진 꼴이 아니면 없는 것으로 본다(값이 Redis 의 패턴으로 쓰이지 않게) */
    private fun chatSessionOf(value: String?): String? = value?.takeIf(ChatService::isSessionId)

    fun recordCounts(requestId: String, visitorToken: String?, chatSessionValue: String?): RecordCounts {
        val quizAttempts = when (val counted = progress.countForDeletion(requestId, visitorToken)) {
            is CountOutcome.Counted -> counted.attempts
            CountOutcome.StorageUnavailable -> null
        }
        val session = chatSessionOf(chatSessionValue)
        val chat: Boolean? = if (session == null) false else try {
            chatHistory.hasHistory(session)
        } catch (error: DataAccessException) {
            requestLog.log(requestId, "quiz my records chat history check failed: ${error.javaClass.simpleName}", "error")
            null
        }
        return RecordCounts(quizAttempts, chat)
    }

    /** 퀴즈 기록(방문자 줄째)과 대화 이력을 함께 지운다 — 한쪽이 실패해도 다른 쪽은 지우고, 무엇이 남았는지 결과에 싣는다 */
    fun deleteMyRecords(requestId: String, visitorToken: String?, chatSessionValue: String?): RecordDeletion {
        val (quiz, deletedAttempts) = if (visitorToken == null) PartDeletion.NONE to 0 else {
            when (val deleted = progress.deleteMine(requestId, visitorToken)) {
                is DeleteOutcome.Deleted -> PartDeletion.DELETED to deleted.attempts
                DeleteOutcome.StorageUnavailable -> PartDeletion.FAILED to null
            }
        }
        val session = chatSessionOf(chatSessionValue)
        val chat = if (session == null) PartDeletion.NONE else try {
            chatHistory.deleteHistory(session)
            PartDeletion.DELETED
        } catch (error: DataAccessException) {
            requestLog.log(requestId, "quiz my records chat history delete failed: ${error.javaClass.simpleName}", "error")
            PartDeletion.FAILED
        }
        val result = RecordDeletion(quiz, deletedAttempts, chat)
        requestLog.log(requestId, "quiz my records deleted quiz=${quiz.name.lowercase()} attempts=$deletedAttempts chat=${chat.name.lowercase()}",
            if (result.complete) "info" else "error")
        return result
    }
}
