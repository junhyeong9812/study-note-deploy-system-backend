package xyz.junproject.backend.progress.engine

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import xyz.junproject.backend.BackendApplication
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
 * 실제 앱 + 실제 PostgreSQL — 명세 11.1: 설정 키(`study.db.*`)로 붙고, 빈 DB 에서 첫 답이 저장되며(표는 앱이 만든다),
 * 의존 서비스 헬스에 postgres 가 UP, 문맥을 닫으면 연결 풀이 닫혀 DB 연결이 남지 않는다(과거에 연결이 바닥났다).
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationEngineTest {
    private val schema = "progress_app_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper.builder().build()

    @AfterAll
    fun close() {
        administrationJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        administration.close()
    }

    /** 이 관리 연결을 뺀 이 DB 의 클라이언트 연결 수 */
    private fun otherConnections() = administrationJdbc.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'",
        Int::class.java)!!

    @Test
    fun `실제 DB 에 붙어 첫 답이 저장되고 의존 서비스 헬스가 UP 이며 문맥을 닫으면 연결이 남지 않는다`() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        val connectionsBefore = otherConnections()
        val context: ConfigurableApplicationContext = SpringApplicationBuilder(BackendApplication::class.java).properties(
            "server.port=0",
            "study.db.url=${EngineSupport.databaseUrl}?currentSchema=$schema",
            "study.db.user=${EngineSupport.databaseUser}",
            "study.db.password=${EngineSupport.databasePassword}",
            "management.health.redis.enabled=false",
        ).run()
        try {
            val service = context.getBean(StudyProgressService::class.java)
            val outcome = service.submitAnswer("req-1", null,
                AnswerSubmission(UUID.randomUUID(), "cs/os/process", 1, "실제 앱의 답", "a".repeat(40), "b".repeat(64)))
            assertTrue(outcome is SubmitOutcome.Saved, outcome.toString())
            assertEquals(1, administrationJdbc.queryForObject("SELECT count(*) FROM $schema.quiz_attempt", Int::class.java))

            val port = context.environment.getProperty("local.server.port")
            val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/health/dependencies"))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString())
            @Suppress("UNCHECKED_CAST")
            val components = json.readValue(response.body(), Map::class.java)["components"] as Map<String, Map<String, Any?>>
            assertEquals("UP", components["postgres"]?.get("status"), response.body())
            assertTrue(otherConnections() > connectionsBefore, "앱이 DB 에 붙지 않았다")
        } finally {
            context.close()
        }
        // 닫힌 연결이 서버에서 사라지기까지 조금 기다린다
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (otherConnections() > connectionsBefore && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(connectionsBefore, otherConnections(), "문맥을 닫았는데 연결이 남았다")
    }
}
