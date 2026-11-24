package xyz.junproject.backend.progress.usecase

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import xyz.junproject.backend.progress.LogCapture
import xyz.junproject.backend.progress.domain.Grade
import xyz.junproject.backend.progress.domain.VisitorToken
import xyz.junproject.backend.progress.infra.ProgressStore
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * 명세 2.1 · 2.2 · 11.2 — 방문자 식별(첫 답에서만 발급 · 해시만 · 목록에 없음과 확인 불가를 가름 · 마지막 방문 갱신),
 * 기록(상한 · 같은 번호는 한 줄 · 저장 실패를 성공으로 보이지 않음 · 남의 기록), 지우기 · 보관 삭제, 로그에 토큰 · 답 본문 없음.
 * 저장소는 메모리 가짜([FakeProgressRecords]) — 실제 PostgreSQL 의 같은 계약은 엔진 테스트가 본다.
 */
class StudyProgressServiceTest {
    private val logs = LogCapture()
    private val records = FakeProgressRecords()

    /** 한국 시각 2026-11-25 10:00 */
    private val now = Instant.parse("2026-11-25T01:00:00Z")
    private val today = LocalDate.of(2026, 11, 25)

    private val firstToken = "F".repeat(42) + "1"
    private val secondToken = "S".repeat(42) + "2"
    private val issuedTokens = ArrayDeque(listOf(firstToken, secondToken))
    private var tokenIssueCount = 0

    private fun service(at: Instant = now, store: ProgressRecords = records) =
        StudyProgressService(store, Clock.fixed(at, ZoneOffset.UTC), logs.requestLog) {
            tokenIssueCount++
            issuedTokens.removeFirst()
        }

    private fun submission(answer: String = "프로세스는 실행 중인 프로그램이다", attemptId: UUID = UUID.randomUUID()) =
        AnswerSubmission(attemptId, "cs/os/process", 3, answer, "a".repeat(40), "b".repeat(64))

    /** 이미 있는 방문자 하나 — 그 토큰 */
    private fun knownVisitor(token: String = "K".repeat(42) + "9", lastVisitOn: LocalDate = today): Pair<String, Visitor> {
        val visitor = records.createVisitor(VisitorToken.hash(token), lastVisitOn, now)
        records.calls.clear()
        return token to visitor
    }

    @AfterEach
    fun closeLogs() = logs.close()

    // ── 식별 ──────────────────────────────────────────────

    @Test
    fun `쿠키가 없으면 기록 없음이고 방문자 줄을 만들지 않는다`() {
        assertEquals(VisitorLookup.Unknown, service().identify("req-1", null))
        assertEquals(emptyList<String>(), records.calls)
        assertEquals(0, tokenIssueCount)
    }

    @Test
    fun `꼴이 틀린 값은 저장소에 묻지 않고 기록 없음이다`() {
        assertEquals(VisitorLookup.Unknown, service().identify("req-1", "not-a-token"))
        assertEquals(VisitorLookup.Unknown, service().identify("req-1", "A".repeat(44)))
        assertEquals(emptyList<String>(), records.calls)
    }

    @Test
    fun `목록에 없는 값은 기록 없음이고 읽기만 하는 요청은 방문자를 만들지 않는다`() {
        assertEquals(VisitorLookup.Unknown, service().identify("req-1", "U".repeat(43)))
        assertEquals(listOf("findVisitor"), records.calls)
        assertTrue(records.visitorsByHash.isEmpty())
        assertEquals(0, tokenIssueCount)
    }

    @Test
    fun `목록에 있는 토큰이면 그 방문자다`() {
        val (token, visitor) = knownVisitor()
        assertEquals(VisitorLookup.Found(visitor), service().identify("req-1", token))
    }

    @Test
    fun `DB 오류로 확인하지 못하면 기록 없음과 다른 확인 불가다`() {
        val (token, _) = knownVisitor()
        records.failing += "findVisitor"
        assertEquals(VisitorLookup.Unavailable, service().identify("req-1", token))
    }

    @Test
    fun `마지막 방문은 한국 날짜가 바뀐 첫 요청에서만 갱신한다`() {
        val (token, visitor) = knownVisitor(lastVisitOn = today)
        // 한국 11-25 23:00 — 같은 날
        service(at = Instant.parse("2026-11-25T14:00:00Z")).identify("req-1", token)
        assertEquals(emptyList<Pair<Long, LocalDate>>(), records.touches)
        // 한국 11-26 00:30(UTC 로는 아직 11-25) — 날이 바뀐 첫 요청
        val nextDay = Instant.parse("2026-11-25T15:30:00Z")
        service(at = nextDay).identify("req-2", token)
        service(at = nextDay.plusSeconds(60)).identify("req-3", token)
        assertEquals(listOf(visitor.id to LocalDate.of(2026, 11, 26)), records.touches)
    }

    @Test
    fun `마지막 방문 갱신이 실패해도 요청은 그 방문자로 이어진다`() {
        val (token, visitor) = knownVisitor(lastVisitOn = today.minusDays(3))
        records.failing += "touchVisit"
        assertEquals(VisitorLookup.Found(visitor), service().identify("req-1", token))
        val outcome = service().submitAnswer("req-2", token, submission())
        assertTrue(outcome is SubmitOutcome.Saved, outcome.toString())
        assertNull((outcome as SubmitOutcome.Saved).issuedToken)
    }

    // ── 답을 냄 ────────────────────────────────────────────

    @Test
    fun `쿠키 없이 첫 답을 내면 토큰을 발급하고 서버에는 해시만 둔다`() {
        val submitted = submission()
        val outcome = service().submitAnswer("req-1", null, submitted)
        assertTrue(outcome is SubmitOutcome.Saved, outcome.toString())
        outcome as SubmitOutcome.Saved
        assertEquals(firstToken, outcome.issuedToken)
        assertEquals(setOf(VisitorToken.hash(firstToken)), records.visitorsByHash.keys)
        assertEquals(StoredAttempt(submitted.attemptId, "cs/os/process", 3, submitted.answer, "a".repeat(40), "b".repeat(64), now, null, null),
            outcome.attempt)
        assertEquals(listOf(outcome.attempt), records.attempts.values.toList())
        assertEquals(records.visitorsByHash.values.single().id, records.attemptOwners[submitted.attemptId])
    }

    @Test
    fun `목록에 없는 토큰으로 답을 내면 그 값을 받지 않고 새 토큰을 발급한다`() {
        val forged = "U".repeat(43)
        val outcome = service().submitAnswer("req-1", forged, submission()) as SubmitOutcome.Saved
        assertEquals(firstToken, outcome.issuedToken)
        assertFalse(records.visitorsByHash.containsKey(VisitorToken.hash(forged)))
        assertEquals(setOf(VisitorToken.hash(firstToken)), records.visitorsByHash.keys)
    }

    @Test
    fun `아는 방문자의 답은 새 토큰 없이 그 방문자의 것으로 저장한다`() {
        val (token, visitor) = knownVisitor()
        val submitted = submission()
        val outcome = service().submitAnswer("req-1", token, submitted) as SubmitOutcome.Saved
        assertNull(outcome.issuedToken)
        assertEquals(0, tokenIssueCount)
        assertEquals(visitor.id, records.attemptOwners[submitted.attemptId])
    }

    @Test
    fun `토큰을 확인하지 못하면 저장하지 않고 새 토큰도 발급하지 않는다`() {
        val (token, _) = knownVisitor()
        records.failing += "findVisitor"
        assertEquals(SubmitOutcome.StorageUnavailable, service().submitAnswer("req-1", token, submission()))
        assertEquals(0, tokenIssueCount)
        assertFalse("createVisitor" in records.calls)
        assertTrue(records.attempts.isEmpty())
    }

    @Test
    fun `첫 답에서 방문자를 만들지 못하면 저장 실패로 알린다`() {
        records.failing += "createVisitor"
        assertEquals(SubmitOutcome.StorageUnavailable, service().submitAnswer("req-1", null, submission()))
        assertTrue(records.attempts.isEmpty())
    }

    @Test
    fun `시도 저장이 실패하면 성공으로 보이지 않고 저장 실패로 알린다`() {
        val (token, _) = knownVisitor()
        records.failing += "recordAttempt"
        assertEquals(SubmitOutcome.StorageUnavailable, service().submitAnswer("req-1", token, submission()))
    }

    @Test
    fun `실제 저장소에 DB 가 닿지 않아도 예외가 아니라 확인 불가와 저장 실패로 돌아온다`() {
        val unreachable = ProgressStore(JdbcTemplate(UnreachableDataSource()))
        val service = service(store = unreachable)
        assertEquals(VisitorLookup.Unavailable, service.identify("req-1", "K".repeat(43)))
        assertEquals(SubmitOutcome.StorageUnavailable, service.submitAnswer("req-2", "K".repeat(43), submission()))
        assertEquals(SubmitOutcome.StorageUnavailable, service.submitAnswer("req-3", null, submission()))
        assertEquals(GradeOutcome.StorageUnavailable, service.grade("req-4", "K".repeat(43), UUID.randomUUID(), Grade.CORRECT))
    }

    @Test
    fun `답의 길이는 4000자까지 저장하고 넘으면 저장하지 않고 그렇다고 답한다`() {
        assertEquals(4_000, StudyProgressService.ANSWER_MAX_CHARS)
        val (token, _) = knownVisitor()
        val atLimit = submission(answer = "가".repeat(4_000))
        val saved = service().submitAnswer("req-1", token, atLimit) as SubmitOutcome.Saved
        assertEquals(4_000, saved.attempt.answer.length)                   // 자르지 않는다
        val overLimit = submission(answer = "가".repeat(4_001))
        assertEquals(SubmitOutcome.AnswerTooLong, service().submitAnswer("req-2", token, overLimit))
        assertFalse(records.attempts.containsKey(overLimit.attemptId))
    }

    @Test
    fun `너무 긴 첫 답은 방문자도 토큰도 만들지 않는다`() {
        assertEquals(SubmitOutcome.AnswerTooLong, service().submitAnswer("req-1", null, submission(answer = "x".repeat(4_001))))
        assertEquals(0, tokenIssueCount)
        assertTrue(records.visitorsByHash.isEmpty())
    }

    @Test
    fun `하루 시도는 1000번까지 저장하고 넘으면 저장하지 않고 그렇다고 답한다`() {
        assertEquals(1_000, StudyProgressService.DAILY_ATTEMPT_LIMIT)
        val (token, visitor) = knownVisitor()
        repeat(999) { records.put(visitor.id, submission(), now.minusSeconds(60)) }
        val thousandth = submission()
        assertTrue(service().submitAnswer("req-1", token, thousandth) is SubmitOutcome.Saved)
        val overLimit = submission()
        assertEquals(SubmitOutcome.DailyLimitReached, service().submitAnswer("req-2", token, overLimit))
        assertFalse(records.attempts.containsKey(overLimit.attemptId))
        assertEquals(1_000, records.attempts.size)
    }

    @Test
    fun `하루의 경계는 한국 시각 자정이다`() {
        val (token, visitor) = knownVisitor()
        val submittedAt = Instant.parse("2026-11-25T16:00:00Z")           // 한국 11-26 01:00
        // 한국 11-25 23:59 에 낸 1000개 — 전날이라 세지 않는다
        repeat(1_000) { records.put(visitor.id, submission(), Instant.parse("2026-11-25T14:59:00Z")) }
        assertTrue(service(at = submittedAt).submitAnswer("req-1", token, submission()) is SubmitOutcome.Saved)

        val (otherToken, other) = knownVisitor(token = "O".repeat(43))
        // 한국 11-26 00:00 에 낸 1000개 — 같은 날이라 센다
        repeat(1_000) { records.put(other.id, submission(), Instant.parse("2026-11-25T15:00:00Z")) }
        assertEquals(SubmitOutcome.DailyLimitReached, service(at = submittedAt).submitAnswer("req-2", otherToken, submission()))
    }

    @Test
    fun `같은 시도 번호로 두 번 내면 한 줄만 남고 같은 결과를 돌려준다`() {
        val (token, _) = knownVisitor()
        val attemptId = UUID.randomUUID()
        val first = service().submitAnswer("req-1", token, submission(answer = "첫 답", attemptId = attemptId)) as SubmitOutcome.Saved
        val second = service(at = now.plusSeconds(5)).submitAnswer("req-2", token, submission(answer = "둘째 답", attemptId = attemptId))
        assertEquals(first, second)
        assertEquals(1, records.attempts.size)
        assertEquals("첫 답", records.attempts.getValue(attemptId).answer)
    }

    @Test
    fun `상한에 닿은 뒤에도 이미 저장한 시도 번호의 재전송은 처음 결과를 돌려준다`() {
        val (token, visitor) = knownVisitor()
        repeat(999) { records.put(visitor.id, submission(), now.minusSeconds(60)) }
        val last = submission()
        val first = service().submitAnswer("req-1", token, last)
        assertTrue(first is SubmitOutcome.Saved)
        assertEquals(first, service().submitAnswer("req-2", token, last))
    }

    @Test
    fun `다른 방문자가 쓴 시도 번호로 내면 남의 답을 돌려주지 않고 거절한다`() {
        val (ownerToken, _) = knownVisitor(token = "A".repeat(43))
        val (otherToken, _) = knownVisitor(token = "B".repeat(43))
        val attemptId = UUID.randomUUID()
        service().submitAnswer("req-1", ownerToken, submission(answer = "주인의 답", attemptId = attemptId))
        val outcome = service().submitAnswer("req-2", otherToken, submission(answer = "남의 답", attemptId = attemptId))
        assertEquals(SubmitOutcome.AttemptIdTaken, outcome)
        assertEquals("주인의 답", records.attempts.getValue(attemptId).answer)
    }

    // ── 채점 ──────────────────────────────────────────────

    @Test
    fun `채점은 그 시도에 덧붙고 시도의 내용은 그대로다`() {
        val (token, _) = knownVisitor()
        val submitted = submission()
        val saved = (service().submitAnswer("req-1", token, submitted) as SubmitOutcome.Saved).attempt
        val gradedAt = now.plusSeconds(30)
        val outcome = service(at = gradedAt).grade("req-2", token, submitted.attemptId, Grade.PARTIAL)
        assertEquals(GradeOutcome.Graded(saved.copy(grade = Grade.PARTIAL, gradedAt = gradedAt)), outcome)
    }

    @Test
    fun `다른 방문자의 시도는 채점할 수 없다`() {
        val (ownerToken, _) = knownVisitor(token = "A".repeat(43))
        val (otherToken, _) = knownVisitor(token = "B".repeat(43))
        val submitted = submission()
        service().submitAnswer("req-1", ownerToken, submitted)
        assertEquals(GradeOutcome.AttemptNotFound, service().grade("req-2", otherToken, submitted.attemptId, Grade.WRONG))
        assertNull(records.attempts.getValue(submitted.attemptId).grade)
    }

    @Test
    fun `모르는 방문자의 채점은 방문자를 만들지 않고 거절한다`() {
        assertEquals(GradeOutcome.VisitorUnknown, service().grade("req-1", null, UUID.randomUUID(), Grade.CORRECT))
        assertEquals(GradeOutcome.VisitorUnknown, service().grade("req-2", "U".repeat(43), UUID.randomUUID(), Grade.CORRECT))
        assertTrue(records.visitorsByHash.isEmpty())
        assertEquals(0, tokenIssueCount)
    }

    @Test
    fun `채점 저장이 실패하면 저장 실패로 알린다`() {
        val (token, _) = knownVisitor()
        val submitted = submission()
        service().submitAnswer("req-1", token, submitted)
        records.failing += "recordGrade"
        assertEquals(GradeOutcome.StorageUnavailable, service().grade("req-2", token, submitted.attemptId, Grade.CORRECT))
    }

    // ── 지우기 · 보관 ─────────────────────────────────────

    @Test
    fun `지워질 시도의 수는 그 방문자의 것만 센다`() {
        val (mineToken, mine) = knownVisitor(token = "A".repeat(43))
        val (_, other) = knownVisitor(token = "B".repeat(43))
        repeat(3) { records.put(mine.id, submission(), now) }
        repeat(2) { records.put(other.id, submission(), now) }
        assertEquals(CountOutcome.Counted(3), service().countForDeletion("req-1", mineToken))
        assertEquals(CountOutcome.Counted(0), service().countForDeletion("req-2", null))
        assertEquals(CountOutcome.Counted(0), service().countForDeletion("req-3", "U".repeat(43)))
        records.failing += "findVisitor"
        assertEquals(CountOutcome.StorageUnavailable, service().countForDeletion("req-4", mineToken))
    }

    @Test
    fun `내 기록 지우기는 그 방문자의 시도와 방문자 줄만 지운다`() {
        val (mineToken, mine) = knownVisitor(token = "A".repeat(43))
        val (otherToken, other) = knownVisitor(token = "B".repeat(43))
        repeat(3) { records.put(mine.id, submission(), now) }
        repeat(2) { records.put(other.id, submission(), now) }
        assertEquals(DeleteOutcome.Deleted(3), service().deleteMine("req-1", mineToken))
        assertFalse(records.visitorsByHash.containsKey(VisitorToken.hash(mineToken)))
        assertTrue(records.visitorsByHash.containsKey(VisitorToken.hash(otherToken)))
        assertEquals(2, records.attemptOwners.values.count { it == other.id })
        assertEquals(0, records.attemptOwners.values.count { it == mine.id })
    }

    @Test
    fun `내 기록 지우기가 DB 오류면 지웠다고 하지 않는다`() {
        val (token, _) = knownVisitor()
        records.failing += "deleteVisitor"
        assertEquals(DeleteOutcome.StorageUnavailable, service().deleteMine("req-1", token))
        records.failing.clear(); records.failing += "findVisitor"
        assertEquals(DeleteOutcome.StorageUnavailable, service().deleteMine("req-2", token))
    }

    @Test
    fun `보관 삭제는 한국 날짜로 1년 전보다 앞선 마지막 방문을 지운다`() {
        assertEquals(java.time.Period.ofYears(1), StudyProgressService.RETENTION)
        knownVisitor(token = "A".repeat(43), lastVisitOn = LocalDate.of(2025, 11, 24))   // 기준 앞 — 지움
        knownVisitor(token = "B".repeat(43), lastVisitOn = LocalDate.of(2025, 11, 25))   // 기준 당일 — 남음
        // 한국 2026-11-25 00:30(UTC 로는 11-24) → 기준 2025-11-25
        val outcome = service(at = Instant.parse("2026-11-24T15:30:00Z")).purgeInactiveVisitors("req-1")
        assertEquals(listOf(LocalDate.of(2025, 11, 25)), records.purgeCutoffs)
        assertEquals(PurgeOutcome.Purged(1), outcome)
    }

    @Test
    fun `보관 삭제가 실패해도 던지지 않고 실패를 로그에 남긴다`() {
        records.failing += "deleteVisitorsLastVisitedBefore"
        assertEquals(PurgeOutcome.Failed, service().purgeInactiveVisitors("req-1"))
        assertTrue(logs.lines.any { it.startsWith("ERROR") || it.startsWith("WARN") }, logs.lines.toString())
    }

    // ── 로그 ──────────────────────────────────────────────

    @Test
    fun `로그에는 토큰 원문과 쓴 답의 본문이 나오지 않는다`() {
        val answer = "답본문-누설되면-안-되는-글귀"
        val forged = "Z".repeat(43)
        val (knownToken, _) = knownVisitor(token = "K".repeat(43), lastVisitOn = today.minusDays(2))
        val known = service()
        known.submitAnswer("req-1", null, submission(answer = answer))                      // 발급
        known.submitAnswer("req-2", forged, submission(answer = answer))                    // 지어낸 값 → 발급
        known.submitAnswer("req-3", knownToken, submission(answer = answer))
        known.submitAnswer("req-4", knownToken, submission(answer = answer + "x".repeat(4_001)))   // 너무 김
        records.failing += setOf("recordAttempt", "touchVisit")
        known.submitAnswer("req-5", knownToken, submission(answer = answer))                // 저장 실패
        known.grade("req-6", knownToken, UUID.randomUUID(), Grade.CORRECT)
        records.failing += "findVisitor"
        known.identify("req-7", knownToken)

        val lines = logs.lines
        assertTrue(lines.any { it.contains("cs/os/process") }, "기록 로그가 하나도 없다 — 주제 · 질문 번호 · 길이 · 결과는 남긴다: $lines")
        listOf(answer, firstToken, secondToken, forged, knownToken).forEach { secret ->
            assertTrue(lines.none { it.contains(secret) }, "로그에 나오면 안 되는 값: $secret\n$lines")
        }
    }

    /** 연결을 줄 수 없는 DB */
    private class UnreachableDataSource : AbstractDataSource() {
        override fun getConnection(): Connection = throw SQLException("Connection refused")
        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }
}
