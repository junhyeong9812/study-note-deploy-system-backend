package xyz.junproject.backend.shared.api

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    constructor(redis: StringRedisTemplate) : this(defaultChecks(redis))

    /** 항상 200 — 어느 것이 안 되는지는 본문의 components 로 알린다(부르는 쪽이 판정한다). */
    @GetMapping("/api/health/dependencies")
    fun dependencies(): Map<String, Any> {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val pending = checks.mapValues { (_, check) -> executor.submit<String?> { runCatching(check).exceptionOrNull()?.let(::describe) } }
        executor.shutdown()
        val deadline = System.nanoTime() + budget.toNanos()
        val components = pending.mapValues { (_, future) ->
            val failure = try {
                future.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: Exception) { "${budget.seconds}초 안에 응답 없음" }
            if (failure == null) mapOf("status" to "UP") else mapOf("status" to "DOWN", "detail" to failure)
        }
        executor.shutdownNow()   // 예산을 넘긴 확인은 버린다 — 한 대상이 느려도 응답이 늦지 않게
        val allUp = components.values.all { it["status"] == "UP" }
        return mapOf("status" to if (allUp) "UP" else "DOWN", "components" to components)
    }

    private fun describe(error: Throwable): String = (error.message ?: error.javaClass.simpleName).take(120)

    companion object {
        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        private fun expectOk(url: String) {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build()
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            check(status == 200) { "status $status" }
        }

        fun defaultChecks(redis: StringRedisTemplate): Map<String, () -> Unit> = linkedMapOf(
            "elasticsearch" to { expectOk((System.getenv("ES_URL") ?: "http://elasticsearch:9200") + "/_cluster/health") },
            "embedding" to { expectOk((System.getenv("EMBEDDING_URL") ?: "http://embedding:8080") + "/health") },
            "redis" to {
                val reply = redis.execute { connection -> connection.ping() }
                check(reply == "PONG") { "ping 응답이 다름: $reply" }
            },
        )
    }
}
