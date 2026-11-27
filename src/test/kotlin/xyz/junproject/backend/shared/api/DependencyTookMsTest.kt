package xyz.junproject.backend.shared.api

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.sql.SQLException
import java.time.Duration

/**
 * 의존 상태의 구성 요소마다 그 확인의 걸린 시간 `took_ms`(⑬ T9a · 규약 §4.3 · §5.2 · llm 의 9b 와 같은 쪽).
 * - `took_ms` 는 **늘 있다**. 그 의존에서 응답을 받았으면(UP 이든, 받은 응답이 쓸 수 없어 DOWN 이든) 그 확인 하나의 걸린 시간 — 단조 시계 · 정수 ms(내림) · 0 이상.
 * - 응답을 받지 못했으면([DependencyUnreachable] — 연결 거절 · 시간 초과 · 끊김) `null`. 전체 예산(3초)을 넘겨 버린 확인도 `null`.
 * - 의존마다 "응답을 받았다": HTTP(ES · 임베딩)는 상태 줄을 받음(200 이 아니어도 · ES red 도) · Redis 는 `ping` 의 답을 받음(PONG 이 아니어도) ·
 *   PostgreSQL 은 연결을 얻고 서버가 답함(SQL 오류도 답이다). 연결을 얻지 못함 · 연결 실패는 받지 못함.
 * - 지금의 것은 그대로: 최상위 `status` · 구성 요소의 `status` · `detail`(DOWN 일 때만, 글은 지금과 같다) · 항상 200 · 병렬과 예산.
 */
class DependencyTookMsTest {
    private val servers = mutableListOf<HttpServer>()
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun close() = servers.forEach { it.stop(0) }

    @Suppress("UNCHECKED_CAST")
    private fun components(body: Map<String, Any>) = body["components"] as Map<String, Map<String, Any?>>

    private fun assertTookNear(tookMs: Any?, lowerMs: Long, upperMs: Long, what: String) {
        assertTrue(tookMs is Int || tookMs is Long, "$what: took_ms 가 정수가 아니다: $tookMs (${tookMs?.javaClass})")
        val value = (tookMs as Number).toLong()
        assertTrue(value in lowerMs..upperMs, "$what: took_ms=$value 가 $lowerMs..$upperMs 밖이다")
    }

    // ---- 경우마다의 took_ms ----

    @Test
    fun `UP 인 확인의 took_ms 는 그 확인의 걸린 시간이고 정수다`() {
        val body = DependencyHealthController(mapOf("elasticsearch" to { Thread.sleep(150) })).dependencies()
        val elasticsearch = components(body)["elasticsearch"]!!
        assertEquals(setOf("status", "took_ms"), elasticsearch.keys, "UP 에는 detail 이 없다")
        assertEquals("UP", elasticsearch["status"])
        assertTookNear(elasticsearch["took_ms"], 140, 1_500, "UP")
    }

    @Test
    fun `응답을 받았지만 쓸 수 없는 DOWN 의 took_ms 는 정수이고 detail 은 지금과 같다`() {
        val body = DependencyHealthController(mapOf("elasticsearch" to { Thread.sleep(150); throw IllegalStateException("status 503") })).dependencies()
        val elasticsearch = components(body)["elasticsearch"]!!
        assertEquals(setOf("status", "took_ms", "detail"), elasticsearch.keys)
        assertEquals("DOWN", elasticsearch["status"])
        assertEquals("status 503", elasticsearch["detail"])
        assertTookNear(elasticsearch["took_ms"], 140, 1_500, "응답을 받은 DOWN")
    }

    @Test
    fun `응답을 받지 못한 DOWN 의 took_ms 는 null 이고 detail 은 그 글 그대로다`() {
        val body = DependencyHealthController(mapOf("embedding" to { Thread.sleep(50); throw DependencyUnreachable("Connection refused") })).dependencies()
        val embedding = components(body)["embedding"]!!
        assertEquals(setOf("status", "took_ms", "detail"), embedding.keys, "DOWN 에도 took_ms 칸이 있다")
        assertNull(embedding["took_ms"])
        assertEquals("DOWN", embedding["status"])
        assertEquals("Connection refused", embedding["detail"])
    }

    @Test
    fun `예산을 넘겨 버린 확인의 took_ms 는 null 이다`() {
        val body = DependencyHealthController(mapOf("embedding" to { Thread.sleep(30_000) }, "redis" to {}), budget = Duration.ofMillis(300)).dependencies()
        val embedding = components(body)["embedding"]!!
        assertEquals("DOWN", embedding["status"])
        assertTrue(embedding.containsKey("took_ms"), "예산을 넘긴 확인에 took_ms 칸이 없다")
        assertNull(embedding["took_ms"], "끝을 보지 못한 확인에 시간을 실었다")
        assertEquals("0초 안에 응답 없음", embedding["detail"], "예산 초과의 detail 이 바뀌었다")
        assertTookNear(components(body)["redis"]!!["took_ms"], 0, 200, "빠른 확인")
    }

    @Test
    fun `took_ms 는 확인마다의 시간이다 - 느린 확인의 시간이 다른 칸에 실리지 않는다`() {
        val body = DependencyHealthController(mapOf(
            "elasticsearch" to { Thread.sleep(400) }, "redis" to {}, "postgres" to { throw IllegalStateException("bad") },
        )).dependencies()
        assertTookNear(components(body)["elasticsearch"]!!["took_ms"], 390, 2_000, "느린 확인")
        assertTookNear(components(body)["redis"]!!["took_ms"], 0, 150, "빠른 확인")
        assertTookNear(components(body)["postgres"]!!["took_ms"], 0, 150, "빠르게 실패한 확인")
    }

    @Test
    fun `확인은 병렬이다 - 200ms 넷이 600ms 안에 끝나고 각 took_ms 는 200 근처다`() {
        val controller = DependencyHealthController(linkedMapOf(
            "elasticsearch" to { Thread.sleep(200) }, "embedding" to { Thread.sleep(200) },
            "redis" to { Thread.sleep(200) }, "postgres" to { Thread.sleep(200) },
        ))
        val started = System.nanoTime()
        val body = controller.dependencies()
        val took = Duration.ofNanos(System.nanoTime() - started).toMillis()
        assertTrue(took < 600, "확인을 차례로 돌렸다: ${took}ms")
        components(body).forEach { (name, component) -> assertTookNear(component["took_ms"], 190, 1_500, name) }
    }

    // ---- 응답의 꼴 · 상태 코드 ----

    @Test
    fun `응답은 늘 200 이고 JSON 의 took_ms 는 정수 숫자이거나 null 이며 다른 칸은 그대로다`() {
        val controller = DependencyHealthController(linkedMapOf(
            "elasticsearch" to {}, "embedding" to { throw DependencyUnreachable("timeout") },
            "redis" to { throw IllegalStateException("ping 응답이 다름: NO") }, "postgres" to { Thread.sleep(30_000) },
        ), budget = Duration.ofMillis(300))
        val response = MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/health/dependencies")).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        val text = response.contentAsString
        val body = json.readValue(text, Map::class.java)
        assertEquals(setOf("status", "components"), body.keys)
        assertEquals("DOWN", body["status"])
        @Suppress("UNCHECKED_CAST") val parsed = body["components"] as Map<String, Map<String, Any?>>
        assertEquals(listOf("elasticsearch", "embedding", "redis", "postgres"), parsed.keys.toList())
        assertEquals(mapOf("status" to "UP"), parsed["elasticsearch"]!! - "took_ms")
        assertEquals(mapOf("status" to "DOWN", "detail" to "timeout", "took_ms" to null), parsed["embedding"])
        assertEquals(mapOf("status" to "DOWN", "detail" to "ping 응답이 다름: NO"), parsed["redis"]!! - "took_ms")
        assertEquals(mapOf("status" to "DOWN", "detail" to "0초 안에 응답 없음", "took_ms" to null), parsed["postgres"])
        assertTrue(Regex("\"elasticsearch\":\\{[^}]*\"took_ms\":\\d+[,}]").containsMatchIn(text), "took_ms 가 정수 숫자로 나가지 않는다: $text")
        assertTrue(Regex("\"redis\":\\{[^}]*\"took_ms\":\\d+[,}]").containsMatchIn(text), text)
        assertFalse(Regex("\"took_ms\":\\d+\\.").containsMatchIn(text), "took_ms 가 소수다: $text")
    }

    @Test
    fun `모두 되면 전체가 UP 이고 모든 칸에 정수 took_ms 가 있다`() {
        val body = DependencyHealthController(linkedMapOf("elasticsearch" to {}, "embedding" to {}, "redis" to {}, "postgres" to {})).dependencies()
        assertEquals("UP", body["status"])
        components(body).forEach { (name, component) ->
            assertEquals(mapOf("status" to "UP"), component - "took_ms", name)
            assertTookNear(component["took_ms"], 0, 200, name)
        }
    }

    // ---- 실제 확인의 갈래 — 의존마다 "응답을 받았다" ----

    private fun httpServer(status: Int, body: String): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4)
        server.createContext("/") { exchange ->
            Thread.sleep(120)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun closedPortUrl(): String = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}"

    private val healthyRedis = mockk<StringRedisTemplate>().also { every { it.execute(any<RedisCallback<String>>()) } returns "PONG" }
    private val healthyJdbc = mockk<JdbcTemplate>().also { every { it.queryForObject("SELECT 1", Int::class.java) } returns 1 }

    private fun realChecks(
        elasticsearchUrl: String = httpServer(200, """{"status":"green"}"""), embeddingUrl: String = httpServer(200, "{}"),
        redis: StringRedisTemplate = healthyRedis, jdbc: JdbcTemplate = healthyJdbc,
    ) = components(DependencyHealthController(DependencyHealthController.defaultChecks(redis, jdbc, elasticsearchUrl, embeddingUrl)).dependencies())

    @Test
    fun `HTTP 확인 - 응답을 받으면 200 이 아니어도 ES red 여도 정수이고 닿지 않으면 null 이다`() {
        val answered = realChecks(elasticsearchUrl = httpServer(200, """{"status":"red"}"""), embeddingUrl = httpServer(503, "down"))
        assertEquals("DOWN", answered["elasticsearch"]!!["status"])
        assertEquals("cluster status red", answered["elasticsearch"]!!["detail"])
        assertTookNear(answered["elasticsearch"]!!["took_ms"], 110, 2_000, "ES red")
        assertEquals("status 503", answered["embedding"]!!["detail"])
        assertTookNear(answered["embedding"]!!["took_ms"], 110, 2_000, "임베딩 503")

        val healthy = realChecks()
        assertEquals("UP", healthy["elasticsearch"]!!["status"])
        assertTookNear(healthy["elasticsearch"]!!["took_ms"], 110, 2_000, "ES green")

        val unreachable = realChecks(elasticsearchUrl = closedPortUrl(), embeddingUrl = closedPortUrl())
        listOf("elasticsearch", "embedding").forEach { name ->
            assertEquals("DOWN", unreachable[name]!!["status"], name)
            assertTrue(unreachable[name]!!.containsKey("took_ms"), name)
            assertNull(unreachable[name]!!["took_ms"], "$name: 닿지 않았는데 시간을 실었다")
            assertNotNull(unreachable[name]!!["detail"], name)
        }
    }

    @Test
    fun `Redis 확인 - 답을 받으면 PONG 이 아니어도 정수이고 연결에 실패하면 null 이다`() {
        val other = mockk<StringRedisTemplate>().also { every { it.execute(any<RedisCallback<String>>()) } returns "NOPE" }
        val answered = realChecks(redis = other)["redis"]!!
        assertEquals("DOWN", answered["status"])
        assertEquals("ping 응답이 다름: NOPE", answered["detail"])
        assertTookNear(answered["took_ms"], 0, 1_000, "Redis 의 다른 답")

        val down = mockk<StringRedisTemplate>().also {
            every { it.execute(any<RedisCallback<String>>()) } throws RedisConnectionFailureException("Unable to connect to Redis")
        }
        val unreachable = realChecks(redis = down)["redis"]!!
        assertEquals("DOWN", unreachable["status"])
        assertNull(unreachable["took_ms"], "연결에 실패했는데 시간을 실었다")
        assertEquals("Unable to connect to Redis", unreachable["detail"], "detail 의 글이 바뀌었다")
        assertTookNear(realChecks()["redis"]!!["took_ms"], 0, 1_000, "Redis PONG")
    }

    @Test
    fun `Redis 확인 - 답을 기다리다 명령 시간 초과로 끝나면 null 이고 detail 은 그 글 그대로다`() {
        // Lettuce 는 연결이 끊겨도 다시 붙을 때까지 명령을 쌓아 두어, Redis 가 멈추거나 죽어도 연결 실패가 아니라 명령 시간 초과로 끝난다
        // (Spring Data Redis 가 RedisCommandTimeoutException 을 QueryTimeoutException 으로 옮긴다)
        val stalled = mockk<StringRedisTemplate>().also {
            every { it.execute(any<RedisCallback<String>>()) } throws org.springframework.dao.QueryTimeoutException("Redis command timed out")
        }
        val timedOut = realChecks(redis = stalled)["redis"]!!
        assertEquals("DOWN", timedOut["status"])
        assertTrue(timedOut.containsKey("took_ms"))
        assertNull(timedOut["took_ms"], "답을 받지 못했는데 시간을 실었다")
        assertEquals("Redis command timed out", timedOut["detail"], "detail 의 글이 바뀌었다")
    }

    @Test
    fun `PostgreSQL 확인 - 질의 시간 초과로 끝나면 null 이다`() {
        // 풀에 남은 연결로 질의가 걸린 경우 — spring-jdbc 가 SQLTimeoutException · SQLState 57014 를 QueryTimeoutException 으로 옮긴다
        val stalled = mockk<JdbcTemplate>().also {
            every { it.queryForObject("SELECT 1", Int::class.java) } throws org.springframework.dao.QueryTimeoutException("PreparedStatementCallback; SQL [SELECT 1]; ERROR: canceling statement due to statement timeout")
        }
        val timedOut = realChecks(jdbc = stalled)["postgres"]!!
        assertEquals("DOWN", timedOut["status"])
        assertNull(timedOut["took_ms"], "답을 받지 못했는데 시간을 실었다")
        assertEquals("PreparedStatementCallback; SQL [SELECT 1]; ERROR: canceling statement due to statement timeout".take(120), timedOut["detail"])
    }

    @Test
    fun `PostgreSQL 확인 - 서버가 답하면 SQL 오류여도 정수이고 연결을 얻지 못하면 null 이다`() {
        val grammar = mockk<JdbcTemplate>().also {
            every { it.queryForObject("SELECT 1", Int::class.java) } throws BadSqlGrammarException("health", "SELECT 1", SQLException("syntax"))
        }
        val answered = realChecks(jdbc = grammar)["postgres"]!!
        assertEquals("DOWN", answered["status"])
        assertTookNear(answered["took_ms"], 0, 1_000, "SQL 오류")

        listOf(
            CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
            DataAccessResourceFailureException("Connection is not available, request timed out after 2000ms"),
        ).forEach { failure ->
            val jdbc = mockk<JdbcTemplate>().also { every { it.queryForObject("SELECT 1", Int::class.java) } throws failure }
            val unreachable = realChecks(jdbc = jdbc)["postgres"]!!
            assertEquals("DOWN", unreachable["status"])
            assertNull(unreachable["took_ms"], "연결을 얻지 못했는데 시간을 실었다: ${failure.javaClass.simpleName}")
            assertEquals(failure.message!!.take(120), unreachable["detail"], "detail 의 글이 바뀌었다")
        }
        assertTookNear(realChecks()["postgres"]!!["took_ms"], 0, 1_000, "SELECT 1")
    }
}
