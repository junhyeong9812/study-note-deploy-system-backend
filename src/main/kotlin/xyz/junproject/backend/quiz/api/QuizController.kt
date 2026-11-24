package xyz.junproject.backend.quiz.api

import xyz.junproject.backend.quiz.usecase.QuizService
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 퀴즈 경로 `/api/quiz/…`(명세 18.1). 저장소를 바로 부르지 않는다 — [QuizService] 로만(명세 18.2).
 * 뼈대 — task 03b 의 구현 워커가 경로를 붙인다(계약은 quiz/api 의 테스트).
 */
class QuizController(
    private val quizService: QuizService,
    private val requestLog: RequestLog,
)
