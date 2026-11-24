package xyz.junproject.backend.quiz.usecase

import xyz.junproject.backend.chat.usecase.ChatHistoryPort
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Clock

/**
 * 퀴즈 경로의 일(명세 18) — 질문 목록 · 답을 냄 · 채점 · 다시 보기 · 오늘 복습할 것 · 지워질 수 · 내 기록 지우기.
 * 저장소는 [StudyProgressService] 로만 닿는다(명세 18.2). 시각은 주입한 시계, 날짜는 한국 시각.
 * 뼈대 — task 03b 의 구현 워커가 채운다(계약은 quiz/api 의 테스트).
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
) {
    companion object {
        const val NEW_VISITORS_PER_MINUTE = 30
        const val REVIEW_ATTEMPT_LIMIT = 5_000
        /** 복습 목록에 싣는 질문 글의 앞부분 — 질문 글의 앞 이만큼(String.take) */
        const val REVIEW_PREVIEW_CHARS = 80
    }
}
