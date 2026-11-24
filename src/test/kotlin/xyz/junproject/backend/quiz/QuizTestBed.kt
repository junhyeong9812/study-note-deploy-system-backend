package xyz.junproject.backend.quiz

import jakarta.servlet.http.Cookie
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.chat.usecase.ChatHistoryPort
import xyz.junproject.backend.content.usecase.CommitInfo
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.FakeProgressRecords
import xyz.junproject.backend.progress.usecase.ProgressRecords
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.progress.usecase.Visitor
import xyz.junproject.backend.quiz.api.QuizController
import xyz.junproject.backend.quiz.domain.QuizSplitResult
import xyz.junproject.backend.quiz.domain.QuizSplitter
import xyz.junproject.backend.quiz.usecase.QuizService
import xyz.junproject.backend.shared.api.GlobalErrorHandler
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** 앞으로 넘길 수 있는 시계 — 속도 제한 · 복습일을 시각으로 본다 */
class MutableClock(var instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
    fun advance(duration: Duration) { instant = instant.plus(duration) }
}

/**
 * 내용 저장소의 가짜 — 지금 판([current] · [head])과 옛 판([history], (SHA, 경로))을 갖는다.
 * 지금 판의 SHA 로 [readFileAt] 을 불러도 지금 판을 준다. 없는 파일 · 모르는 SHA 는 FileNotFoundException(GitRepository 와 같은 계약).
 * 읽은 것은 [reads](지금 판 경로) · [readsAt](SHA 와 경로)에 남는다.
 */
class FakeNoteSource : NoteSourcePort {
    var head: String = QuizContent.HEAD
    val current = linkedMapOf<String, String>()
    val history = linkedMapOf<Pair<String, String>, String>()
    val reads = mutableListOf<String>()
    val readsAt = mutableListOf<Pair<String, String>>()

    override fun allMarkdown(): List<String> = current.keys.sorted()
    override fun currentHead(): String = head
    /**
     * 다음 작업 트리 읽기 직전에 한 번 돈다 — 내용 동기화가 "지금 판의 SHA 를 읽은 뒤 · 파일을 읽기 전"에 끼어드는 순간을 만든다
     * (그 안에서 [head] 와 [current] 를 바꾼다). 한 번 돌면 비워진다.
     */
    var syncBeforeNextRead: (() -> Unit)? = null

    override fun readFile(path: String): String {
        syncBeforeNextRead?.let { sync -> syncBeforeNextRead = null; sync() }
        reads += path
        return current[path] ?: throw FileNotFoundException(path)
    }
    override fun recentCommits(limit: Int): List<CommitInfo> = emptyList()
    override fun readFileAt(sha: String, path: String): String {
        readsAt += sha to path
        if (sha == head) return current[path] ?: throw FileNotFoundException("$sha:$path")
        return history[sha to path] ?: throw FileNotFoundException("$sha:$path")
    }

    /** 아무 판에서든 읽힌 경로 */
    fun everyReadPath(): List<String> = reads + readsAt.map { it.second }
}

/** 대화 이력의 가짜 — [sessionsWithHistory] 에 든 세션만 이력이 있다. [failing] 에 든 함수 이름은 Redis 오류로 실패한다 */
class FakeChatHistory : ChatHistoryPort {
    val sessionsWithHistory = linkedSetOf<String>()
    val failing = mutableSetOf<String>()
    val calls = mutableListOf<Pair<String, String>>()

    private fun call(name: String, sessionId: String) {
        calls += name to sessionId
        if (name in failing) throw RedisConnectionFailureException("redis down (fake)")
    }

    override fun hasHistory(sessionId: String): Boolean { call("hasHistory", sessionId); return sessionId in sessionsWithHistory }
    override fun deleteHistory(sessionId: String) { call("deleteHistory", sessionId); sessionsWithHistory.remove(sessionId) }
}

/**
 * 테스트 자료 — cs 주제 셋과 cs 밖 주제 하나. 정답 파일의 글에는 `ANSWER-MARK` · `ANSWER-TITLE` 표식이 있어
 * 응답 글 전체에서 정답이 새는지 찾는다. 질문 2 는 옛 판([OLD_SHA])에서 글이 달랐다.
 */
object QuizContent {
    const val HEAD = "1111111111111111111111111111111111111111"
    const val OLD_SHA = "2222222222222222222222222222222222222222"
    /** 내용이 바뀐 뒤의 새 판 */
    const val NEW_SHA = "4444444444444444444444444444444444444444"
    /** 얕은 사본에 없는(읽을 수 없는) SHA */
    const val UNREADABLE_SHA = "3333333333333333333333333333333333333333"

    const val TOPIC = "cs/os/process"
    const val OTHER_TOPIC = "cs/net/tcp"
    /** 정답이 모자라 쪼갤 수 없는 주제(질문 1 · 2, 정답 1) */
    const val BROKEN_TOPIC = "cs/broken/missing-answer"
    /** cs 밖 — 파일은 있지만 이번 범위가 아니다 */
    const val OUTSIDE_TOPIC = "languages/kotlin/basics"
    /** 기록은 있으나 지금은 없어진 주제 */
    const val GONE_TOPIC = "cs/gone/removed-topic"

    /** 정답 자료의 표식 — 이 글자가 보이면 정답이 샌 것 */
    val ANSWER_MARKERS = listOf("ANSWER-MARK", "ANSWER-TITLE")

    val PROCESS_QUESTIONS = """
        # cs/os/process — 질문

        ## 질문

        ### A. 개념

        1. QUESTION-MARK-1 프로세스란 무엇인가?
        2. QUESTION-MARK-2 스레드와 프로세스는 어떻게 다른가?

        ### B. 응용

        3. QUESTION-MARK-3 문맥 교환이 비싼 까닭은 무엇인가? 이 질문은 복습 목록에 싣는 앞부분 자르기를 보려고 일부러 길게 쓴다. 레지스터와 캐시와 TLB 를 함께 생각한다.
           - 힌트: 커널 모드로 들어가는 비용도 있다.

        ## 복습 기록

        | 날짜 | 결과 |
        |------|------|
    """.trimIndent() + "\n"

    val PROCESS_ANSWERS = """
        # cs/os/process — 정답

        ## 정답

        ### 1. ANSWER-TITLE-1 실행 중인 프로그램

        ANSWER-MARK-1 프로세스는 실행 중인 프로그램이다.

        ### 2. ANSWER-TITLE-2 자원의 공유

        ANSWER-MARK-2 스레드는 주소 공간을 함께 쓴다.

        ### 3. ANSWER-TITLE-3 문맥 교환

        ANSWER-MARK-3 레지스터와 캐시를 바꾼다.
    """.trimIndent() + "\n"

    /** 옛 판 — 질문 2 의 글과 정답 2 가 달랐다 */
    val OLD_PROCESS_QUESTIONS = PROCESS_QUESTIONS.replace("2. QUESTION-MARK-2 스레드와 프로세스는 어떻게 다른가?", "2. QUESTION-MARK-2 (옛 판) 스레드란 무엇인가?")
    val OLD_PROCESS_ANSWERS = PROCESS_ANSWERS.replace("ANSWER-MARK-2 스레드는 주소 공간을 함께 쓴다.", "ANSWER-MARK-OLD-2 스레드는 실행의 단위다.")

    val TCP_QUESTIONS = """
        # cs/net/tcp — 질문

        ## 질문

        1. QUESTION-MARK-TCP-1 3-way handshake 는 왜 세 번인가?
        2. QUESTION-MARK-TCP-2 TIME_WAIT 는 왜 있는가?
    """.trimIndent() + "\n"

    val TCP_ANSWERS = """
        ## 정답

        ### 1. ANSWER-TITLE-TCP-1

        ANSWER-MARK-TCP-1 양쪽의 순서 번호를 맞춘다.

        ### 2. ANSWER-TITLE-TCP-2

        ANSWER-MARK-TCP-2 늦게 온 조각을 흘려보낸다.
    """.trimIndent() + "\n"

    val BROKEN_QUESTIONS = """
        ## 질문

        1. QUESTION-MARK-BROKEN-1 첫 질문
        2. QUESTION-MARK-BROKEN-2 정답이 없는 질문
    """.trimIndent() + "\n"

    val BROKEN_ANSWERS = """
        ## 정답

        ### 1. ANSWER-TITLE-BROKEN-1

        ANSWER-MARK-BROKEN-1 하나뿐인 정답
    """.trimIndent() + "\n"

    fun install(notes: FakeNoteSource) {
        notes.current["$TOPIC/1-question.md"] = PROCESS_QUESTIONS
        notes.current["$TOPIC/2-summary.md"] = "# 요약\n"
        notes.current["$TOPIC/3-answer.md"] = PROCESS_ANSWERS
        notes.current["$OTHER_TOPIC/1-question.md"] = TCP_QUESTIONS
        notes.current["$OTHER_TOPIC/3-answer.md"] = TCP_ANSWERS
        notes.current["$BROKEN_TOPIC/1-question.md"] = BROKEN_QUESTIONS
        notes.current["$BROKEN_TOPIC/3-answer.md"] = BROKEN_ANSWERS
        notes.current["$OUTSIDE_TOPIC/1-question.md"] = TCP_QUESTIONS
        notes.current["$OUTSIDE_TOPIC/3-answer.md"] = TCP_ANSWERS
        notes.history[OLD_SHA to "$TOPIC/1-question.md"] = OLD_PROCESS_QUESTIONS
        notes.history[OLD_SHA to "$TOPIC/3-answer.md"] = OLD_PROCESS_ANSWERS
    }

    private fun split(questions: String, answers: String) = QuizSplitter.split(questions, answers) as QuizSplitResult.Split

    val processSplit get() = split(PROCESS_QUESTIONS, PROCESS_ANSWERS)
    val oldProcessSplit get() = split(OLD_PROCESS_QUESTIONS, OLD_PROCESS_ANSWERS)
    val tcpSplit get() = split(TCP_QUESTIONS, TCP_ANSWERS)

    /** 지금 판의 질문 해시 */
    fun hashOf(topic: String, number: Int): String = when (topic) {
        TOPIC -> processSplit
        OTHER_TOPIC -> tcpSplit
        else -> error("자료에 없는 주제 $topic")
    }.questions.single { it.number == number }.hash

    /** 옛 판의 질문 2 해시 — 지금 판과 다르다 */
    val oldQuestionTwoHash get() = oldProcessSplit.questions.single { it.number == 2 }.hash

    /** 어느 판에도 없는 해시 */
    const val UNKNOWN_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
}

/**
 * 퀴즈 경로를 HTTP 로 부르는 시험대 — 실제 [QuizController] · [QuizService] · [StudyProgressService] 위에
 * 가짜 저장소([FakeProgressRecords] — 엔진 테스트는 실제 저장소) · 가짜 내용([FakeNoteSource]) · 가짜 대화 이력([FakeChatHistory]).
 * 발급되는 토큰은 차례로 `T…000001`, `T…000002` … (43글자 — 꼴이 맞다).
 */
class QuizTestBed(
    val records: ProgressRecords = FakeProgressRecords(),
    start: Instant = NOW,
    newVisitorsPerMinute: Int? = null,
    reviewAttemptLimit: Int? = null,
) : AutoCloseable {
    companion object {
        /** 한국 시각 2026-11-25 10:00 — 분의 시작 */
        val NOW: Instant = Instant.parse("2026-11-25T01:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 11, 25)
        const val CHAT_COOKIE = "chat_session"
        val json: JsonMapper = JsonMapper.builder().build()
    }

    val clock = MutableClock(start)
    val logs = LogCapture()
    val notes = FakeNoteSource().also(QuizContent::install)
    val chatHistory = FakeChatHistory()
    val issuedTokens = mutableListOf<String>()

    val progress = StudyProgressService(records, clock, logs.requestLog, issueToken = {
        ("T".repeat(37) + "%06d".format(issuedTokens.size + 1)).also { issuedTokens += it }
    })

    val quizService: QuizService = when {
        newVisitorsPerMinute != null && reviewAttemptLimit != null -> QuizService(notes, progress, chatHistory, clock, logs.requestLog,
            newVisitorsPerMinute = newVisitorsPerMinute, reviewAttemptLimit = reviewAttemptLimit)
        newVisitorsPerMinute != null -> QuizService(notes, progress, chatHistory, clock, logs.requestLog, newVisitorsPerMinute = newVisitorsPerMinute)
        reviewAttemptLimit != null -> QuizService(notes, progress, chatHistory, clock, logs.requestLog, reviewAttemptLimit = reviewAttemptLimit)
        else -> QuizService(notes, progress, chatHistory, clock, logs.requestLog)
    }

    val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(QuizController(quizService, logs.requestLog))
        .setControllerAdvice(GlobalErrorHandler(logs.requestLog)).build()

    /** 가짜 저장소일 때의 그것 */
    val fake: FakeProgressRecords get() = records as FakeProgressRecords

    override fun close() = logs.close()

    // ── 부르기 ──────────────────────────────────────────

    fun perform(request: RequestBuilder): MockHttpServletResponse = mockMvc.perform(request).andReturn().response

    private fun withCookies(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, cookies: Array<out Cookie>) =
        builder.apply { if (cookies.isNotEmpty()) cookie(*cookies) }

    fun questions(topic: String, vararg cookies: Cookie) =
        perform(withCookies(MockMvcRequestBuilders.get("/api/quiz/questions").param("topic", topic), cookies))

    fun submit(body: Map<String, Any?>, vararg cookies: Cookie) =
        perform(withCookies(MockMvcRequestBuilders.post("/api/quiz/answers").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)), cookies))

    fun grade(body: Map<String, Any?>, vararg cookies: Cookie) =
        perform(withCookies(MockMvcRequestBuilders.post("/api/quiz/grades").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)), cookies))

    fun revisit(topic: String, number: String, vararg cookies: Cookie) =
        perform(withCookies(MockMvcRequestBuilders.get("/api/quiz/answers").param("topic", topic).param("number", number), cookies))

    fun review(vararg cookies: Cookie) = perform(withCookies(MockMvcRequestBuilders.get("/api/quiz/review"), cookies))

    fun myRecords(vararg cookies: Cookie) = perform(withCookies(MockMvcRequestBuilders.get("/api/quiz/my-records"), cookies))

    fun deleteMyRecords(vararg cookies: Cookie) = perform(withCookies(MockMvcRequestBuilders.delete("/api/quiz/my-records"), cookies))

    /** 지금 판의 질문에 맞는 답 요청의 본문 */
    fun answerBody(
        number: Int = 2,
        answer: String = "스레드는 프로세스 안의 실행 흐름이다",
        attemptId: Any? = UUID.randomUUID().toString(),
        topic: String = QuizContent.TOPIC,
        contentSha: String = QuizContent.HEAD,
        questionHash: String = QuizContent.hashOf(QuizContent.TOPIC, number),
    ): Map<String, Any?> = linkedMapOf(
        "attempt_id" to attemptId, "topic" to topic, "question_number" to number,
        "answer" to answer, "content_sha" to contentSha, "question_hash" to questionHash,
    )

    // ── 준비(가짜 저장소에 바로 넣는다 — 부른 것으로 세지 않는다) ──

    fun visitorCookie(token: String) = Cookie("study_visitor", token)

    /** 이미 있는 방문자 하나 — 그 토큰과 줄 */
    fun knownVisitor(token: String = "K".repeat(40) + "%03d".format(fake.visitorsByHash.size + 1)): Pair<String, Visitor> {
        val visitor = fake.createVisitor(VisitorToken.hash(token), TODAY, NOW)
        fake.calls.clear()
        return token to visitor
    }

    /** 시도 한 줄을 바로 넣는다(채점이 있으면 낸 시각 1분 뒤로 붙인다) */
    fun putAttempt(
        visitor: Visitor, topic: String, number: Int, submittedAt: Instant, grade: Grade? = null,
        questionHash: String = QuizContent.hashOf(topic, number), answer: String = "준비한 답 $topic $number",
        contentSha: String = QuizContent.HEAD,
    ): UUID {
        val attemptId = UUID.randomUUID()
        fake.put(visitor.id, AnswerSubmission(attemptId, topic, number, answer, contentSha, questionHash), submittedAt)
        if (grade != null) fake.putGrade(attemptId, grade, submittedAt.plusSeconds(60))
        return attemptId
    }

    // ── 응답 읽기 ──────────────────────────────────────

    fun body(response: MockHttpServletResponse): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return json.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun data(response: MockHttpServletResponse): Map<String, Any?> = body(response)["data"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun errorCode(response: MockHttpServletResponse): Any? = (body(response)["error"] as Map<String, Any?>?)?.get("code")

    /** 응답의 Set-Cookie 를 쿠키 이름 → 그 줄 전체 */
    fun setCookies(response: MockHttpServletResponse): Map<String, String> {
        val lines = response.getHeaders("Set-Cookie")
        val byName = lines.groupBy { it.substringBefore("=") }
        byName.forEach { (name, sameName) -> check(sameName.size == 1) { "같은 쿠키를 두 번 놓았다: $name $lines" } }
        return byName.mapValues { it.value.single() }
    }

    /**
     * 놓은 쿠키가 [expected] 와 같은가 — 이름=값과 속성(Expires 는 뺀다: 시험 응답이 날짜의 꼴을 바꿔 적고, 값은 Max-Age 와 같은 뜻이다).
     * [actualLine] 이 null 이면 놓지 않은 것이다.
     */
    fun assertSameCookie(expected: org.springframework.http.ResponseCookie, actualLine: String?) {
        org.junit.jupiter.api.Assertions.assertNotNull(actualLine, "${expected.name} 를 놓지 않았다")
        val expectedLine = expected.toString()
        org.junit.jupiter.api.Assertions.assertEquals(expectedLine.substringBefore(";"), actualLine!!.substringBefore(";"), actualLine)
        org.junit.jupiter.api.Assertions.assertEquals(attributesOf(expectedLine) - "expires", attributesOf(actualLine) - "expires", actualLine)
    }

    /** Set-Cookie 한 줄의 속성(소문자 이름 → 값) */
    fun attributesOf(setCookieLine: String): Map<String, String> =
        setCookieLine.split(";").map { it.trim() }.filter { it.isNotEmpty() }.drop(1).associate { part ->
            part.substringBefore("=").lowercase() to (if (part.contains("=")) part.substringAfter("=") else "")
        }
}
