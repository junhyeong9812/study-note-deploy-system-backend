package xyz.junproject.backend.quiz

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.junproject.backend.chat.usecase.ChatHistoryPort
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.quiz.usecase.QuizService
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Clock

/** 퀴즈 경로의 조립 — 기록은 [StudyProgressService] 로만(저장소 · DB 연결을 받지 않는다, 명세 18.2). 상한은 QuizService 의 기본값 */
@Configuration
class QuizConfiguration {
    @Bean
    fun quizService(notes: NoteSourcePort, studyProgressService: StudyProgressService, chatHistory: ChatHistoryPort, requestLog: RequestLog) =
        QuizService(notes, studyProgressService, chatHistory, Clock.systemUTC(), requestLog)
}
