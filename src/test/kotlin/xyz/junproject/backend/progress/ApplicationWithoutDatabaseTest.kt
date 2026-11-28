package xyz.junproject.backend.progress

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.progress.usecase.AnswerSubmission
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.progress.usecase.SubmitOutcome
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * 명세 11.1 · 2.2 — DB 가 죽어 있어도 앱은 뜨고(연결 풀이 기동을 막지 않는다), 기본 헬스(`/actuator/health`)는 DB 에 매이지 않으며
 * (자리 전환이 막히지 않게), 의존 서비스 헬스에는 postgres 가 DOWN 으로 나오고, 저장은 예외가 아니라 저장 실패로 돌아온다.
 * 퀴즈 경로(명세 18)가 실제 앱에 조립되어 있다. DB 주소는 아무도 듣지 않는 포트. Redis 헬스는 끈다 — 이 테스트 환경에 Redis 가 없어 기본 헬스가 그 때문에 DOWN 이 되지 않게.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "study.db.url=jdbc:postgresql://127.0.0.1:1/study",
        "study.db.user=study",
        "study.db.password=unused",
        "management.health.redis.enabled=false",
    ],
)
@DirtiesContext
class ApplicationWithoutDatabaseTest {
    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var studyProgressService: StudyProgressService

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper.builder().build()

    private fun get(path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:${environment.getProperty("local.server.port")}$path"))
            .timeout(Duration.ofSeconds(30)).GET().build(),
        HttpResponse.BodyHandlers.ofString())

    @Test
    fun `DB 가 죽어 있어도 앱이 뜨고 기본 헬스는 UP 이다`() {
        val response = get("/actuator/health")
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("UP", json.readValue(response.body(), Map::class.java)["status"], response.body())
    }

    @Test
    fun `의존 서비스 헬스에 postgres 가 DOWN 으로 나온다`() {
        val response = get("/api/health/dependencies")
        assertEquals(200, response.statusCode(), response.body())
        @Suppress("UNCHECKED_CAST")
        val components = json.readValue(response.body(), Map::class.java)["components"] as Map<String, Map<String, Any?>>
        assertEquals("DOWN", components["postgres"]?.get("status"), response.body())
        assertTrue(components.keys.containsAll(listOf("elasticsearch", "embedding", "redis")), response.body())   // 기존 대상은 그대로
    }

    @Test
    fun `퀴즈 경로가 앱에 붙어 있다 - 쿠키 없는 복습 목록은 DB 없이 빈 목록이고 잘못된 주제 경로는 422 다`() {
        val review = get("/api/quiz/review")
        assertEquals(200, review.statusCode(), review.body())
        val body = json.readValue(review.body(), Map::class.java)
        assertEquals(true, body["success"], review.body())
        assertEquals(emptyList<Any>(), (body["data"] as Map<*, *>)["items"], review.body())
        assertTrue(review.headers().allValues("Set-Cookie").isEmpty(), review.headers().toString())

        val rejected = get("/api/quiz/questions?topic=cs/..%2F..%2Fetc")
        assertEquals(422, rejected.statusCode(), rejected.body())
    }

    @Test
    fun `DB 가 죽어 있으면 답 저장은 예외가 아니라 저장 실패로 돌아온다`() {
        val submission = AnswerSubmission(UUID.randomUUID(), "cs/os/process", 1, "답", "a".repeat(40), "b".repeat(64))
        assertEquals(SubmitOutcome.StorageUnavailable, studyProgressService.submitAnswer("req-1", null, submission))
    }
}
