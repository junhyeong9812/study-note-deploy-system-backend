package xyz.junproject.backend.quiz.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.backend.progress.usecase.ProgressRecords
import xyz.junproject.backend.quiz.usecase.QuizService
import javax.sql.DataSource

/**
 * 명세 18.2 — 저장소는 서비스로만: 컨트롤러는 저장소(`ProgressRecords`)를 바로 부르지 않는다(DB 오류가 500 이 되지 않게).
 * 퀴즈 경로의 두 층 어느 생성자도 저장소 · DB 연결을 받지 않는다 — 기록은 `StudyProgressService` 를 거친다.
 */
class QuizStructureTest {
    private val storageTypes = listOf(ProgressRecords::class.java, JdbcTemplate::class.java, DataSource::class.java)

    private fun constructorParameterTypes(type: Class<*>) = type.declaredConstructors.flatMap { it.parameterTypes.toList() }

    @Test
    fun `QuizController 는 저장소와 DB 연결을 생성자로 받지 않고 QuizService 를 받는다`() {
        val parameters = constructorParameterTypes(QuizController::class.java)
        storageTypes.forEach { storage ->
            assertTrue(parameters.none { storage.isAssignableFrom(it) || it.isAssignableFrom(storage) }, "$storage 를 받는다: $parameters")
        }
        assertTrue(QuizService::class.java in parameters, parameters.toString())
    }

    @Test
    fun `QuizService 는 저장소와 DB 연결을 생성자로 받지 않는다`() {
        val parameters = constructorParameterTypes(QuizService::class.java)
        storageTypes.forEach { storage ->
            assertTrue(parameters.none { storage.isAssignableFrom(it) || it.isAssignableFrom(storage) }, "$storage 를 받는다: $parameters")
        }
    }
}
