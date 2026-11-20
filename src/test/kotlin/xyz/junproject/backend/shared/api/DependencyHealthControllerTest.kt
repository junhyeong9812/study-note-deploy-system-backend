package xyz.junproject.backend.shared.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class DependencyHealthControllerTest {
    private fun components(body: Map<String, Any>) = @Suppress("UNCHECKED_CAST") (body["components"] as Map<String, Map<String, String>>)

    @Test
    fun `모두 되면 전체와 각 구성 요소가 UP`() {
        val body = DependencyHealthController(mapOf("elasticsearch" to {}, "redis" to {})).dependencies()
        assertEquals("UP", body["status"])
        assertEquals(mapOf("elasticsearch" to mapOf("status" to "UP"), "redis" to mapOf("status" to "UP")), components(body))
    }

    @Test
    fun `하나가 안 되면 그것만 DOWN 이고 이유가 붙는다`() {
        val body = DependencyHealthController(mapOf(
            "elasticsearch" to { throw IllegalStateException("status 503") }, "embedding" to {})).dependencies()
        assertEquals("DOWN", body["status"])
        assertEquals(mapOf("status" to "DOWN", "detail" to "status 503"), components(body)["elasticsearch"])
        assertEquals(mapOf("status" to "UP"), components(body)["embedding"])
    }

    @Test
    fun `느린 대상 하나가 응답 전체를 붙잡지 않는다`() {
        val controller = DependencyHealthController(
            mapOf("embedding" to { Thread.sleep(30_000) }, "redis" to {}), budget = Duration.ofMillis(300))
        val started = System.nanoTime()
        val body = controller.dependencies()
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        assertEquals("DOWN", components(body)["embedding"]?.get("status"))
        assertEquals("UP", components(body)["redis"]?.get("status"))
    }
}
