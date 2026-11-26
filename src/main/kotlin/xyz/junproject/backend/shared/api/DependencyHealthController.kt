package xyz.junproject.backend.shared.api

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 그 의존에서 **응답을 받지 못한** 확인의 실패(연결 거절 · 연결 · 읽기 시간 초과 · 연결 끊김) — 걸린 시간 `took_ms` 는 null 이다.
 * 응답을 받았지만 쓸 수 없는 것(상태 코드가 200 이 아님 · 클러스터 red · 다른 답)은 다른 예외로 던지고 `took_ms` 는 정수다(⑬ T9a).
 * [message] 는 지금처럼 `detail` 이 된다(120자까지)
 */
class DependencyUnreachable(message: String) : RuntimeException(message)

/**
 * 의존 서비스 상태 — ES·임베딩은 이 서버 안에서만 열려 있어 밖(알림 서버)에서 직접 볼 수 없다.
 *
 * actuator 의 헬스 지표로 넣지 않고 별도 경로로 둔다: 지표로 넣으면 기본 `/actuator/health` 합산에 들어가
 * 의존 서비스가 죽었을 때 이 앱의 배포 헬스 판정까지 바뀐다(앱 자체는 살아 있는데 배포가 막힌다).
 */
@RestController
class DependencyHealthController(
    private val checks: Map<String, () -> Unit>,
    private val budget: Duration = Duration.ofSeconds(3),
) {
    @Autowired
    constructor(redis: StringRedisTemplate, studyJdbc: JdbcTemplate) : this(defaultChecks(redis, studyJdbc))

    /** 확인 하나의 결과 — [failure] 는 DOWN 의 detail(UP 이면 null), [tookMillis] 는 응답을 받지 못했으면 null */
    private data class Outcome(val failure: String?, val tookMillis: Long?)

    /** 항상 200 — 어느 것이 안 되는지는 본문의 components 로 알린다(부르는 쪽이 판정한다). 구성 요소마다 그 확인의 걸린 시간 took_ms */
    @GetMapping("/api/health/dependencies")
    fun dependencies(): Map<String, Any> {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        // 시간은 확인을 감싸는 작업 안에서 잰다(단조 시계) — 병렬이라 한 확인의 느림이 다른 칸에 실리지 않는다
        val pending = checks.mapValues { (_, check) -> executor.submit<Outcome> { measured(check) } }
        executor.shutdown()
        val deadline = System.nanoTime() + budget.toNanos()
        val components = pending.mapValues { (_, future) ->
            val outcome = try {
                future.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: Exception) { Outcome("${budget.seconds}초 안에 응답 없음", tookMillis = null) }   // 끝을 보지 못한 확인
            if (outcome.failure == null) mapOf("status" to "UP", "took_ms" to outcome.tookMillis)
            else mapOf("status" to "DOWN", "took_ms" to outcome.tookMillis, "detail" to outcome.failure)
        }
        executor.shutdownNow()   // 예산을 넘긴 확인은 버린다 — 한 대상이 느려도 응답이 늦지 않게
        val allUp = components.values.all { it["status"] == "UP" }
        return mapOf("status" to if (allUp) "UP" else "DOWN", "components" to components)
    }

    private fun describe(error: Throwable): String = (error.message ?: error.javaClass.simpleName).take(120)

    /** 확인 하나를 돌려 잰다 — 응답을 받지 못한 실패([DependencyUnreachable])는 걸린 시간이 없다 */
    private fun measured(check: () -> Unit): Outcome {
        val started = System.nanoTime()
        val error = runCatching(check).exceptionOrNull()
        val tookMillis = if (error is DependencyUnreachable) null else (System.nanoTime() - started) / 1_000_000
        return Outcome(error?.let(::describe), tookMillis)
    }

    companion object {
        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        /** 받지 못한 실패를 [DependencyUnreachable] 로 — 글은 원래 예외의 것(없으면 종류 이름)이라 detail 이 지금과 같다 */
        private fun unreachable(error: Exception) = DependencyUnreachable(error.message ?: error.javaClass.simpleName)

        private fun expectOk(url: String): String {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build()
            // 연결 거절 · 연결 · 읽기 시간 초과 · 끊김 — 상태 줄을 받지 못했다
            val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) } catch (error: IOException) { throw unreachable(error) }
            check(response.statusCode() == 200) { "status ${response.statusCode()}" }
            return response.body()
        }

        /** ES 는 클러스터가 red(검색 불가)여도 200 을 준다 — 본문의 status 까지 본다. */
        internal fun requireUsableCluster(clusterHealthBody: String) {
            check(!Regex(""""status"\s*:\s*"red"""").containsMatchIn(clusterHealthBody)) { "cluster status red" }
        }

        fun defaultChecks(
            redis: StringRedisTemplate, studyJdbc: JdbcTemplate,
            elasticsearchUrl: String = System.getenv("ES_URL") ?: "http://elasticsearch:9200",
            embeddingUrl: String = System.getenv("EMBEDDING_URL") ?: "http://embedding:8080",
        ): Map<String, () -> Unit> = linkedMapOf(
            "elasticsearch" to {
                requireUsableCluster(expectOk("$elasticsearchUrl/_cluster/health"))
            },
            "embedding" to { expectOk("$embeddingUrl/health"); Unit },
            "redis" to {
                val reply = try {
                    redis.execute { connection -> connection.ping() }
                } catch (error: DataAccessResourceFailureException) { throw unreachable(error) }   // 연결에 실패 — 답을 받지 못했다
                check(reply == "PONG") { "ping 응답이 다름: $reply" }
            },
            // 공부 기록 DB — 기본 헬스에는 넣지 않는다(DB 가 죽어도 읽기는 되고 자리 전환이 막히지 않게, 명세 11.1)
            "postgres" to {
                // 연결을 얻지 못함은 받지 못함 — 서버가 답한 SQL 오류는 응답을 받은 것이다
                try { studyJdbc.queryForObject("SELECT 1", Int::class.java) } catch (error: DataAccessResourceFailureException) { throw unreachable(error) }
                Unit
            },
        )
    }
}
