package xyz.junproject.backend.shared.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class DependencyHealthControllerTest {
    private fun components(body: Map<String, Any>) = @Suppress("UNCHECKED_CAST") (body["components"] as Map<String, Map<String, Any?>>)

    /** 구성 요소의 칸 가운데 걸린 시간(⑬ T9a — DependencyTookMsTest 가 따로 본다)을 뺀 나머지 — 지금의 칸은 값까지 그대로 견준다 */
    private fun withoutTookMs(component: Map<String, Any?>?): Map<String, Any?>? {
        assertTrue(component?.containsKey("took_ms") == true, "took_ms 칸이 없다: $component")
        return component?.minus("took_ms")
    }

    @Test
    fun `모두 되면 전체와 각 구성 요소가 UP`() {
        val body = DependencyHealthController(mapOf("elasticsearch" to {}, "redis" to {})).dependencies()
        assertEquals("UP", body["status"])
        assertEquals(setOf("elasticsearch", "redis"), components(body).keys)
        assertEquals(mapOf("status" to "UP"), withoutTookMs(components(body)["elasticsearch"]))
        assertEquals(mapOf("status" to "UP"), withoutTookMs(components(body)["redis"]))
    }

    @Test
    fun `하나가 안 되면 그것만 DOWN 이고 이유가 붙는다`() {
        val body = DependencyHealthController(mapOf(
            "elasticsearch" to { throw IllegalStateException("status 503") }, "embedding" to {})).dependencies()
        assertEquals("DOWN", body["status"])
        assertEquals(mapOf("status" to "DOWN", "detail" to "status 503"), withoutTookMs(components(body)["elasticsearch"]))
        assertEquals(mapOf("status" to "UP"), withoutTookMs(components(body)["embedding"]))
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

    @Test
    fun `ES 클러스터가 red 면 200 이어도 안 되는 것으로 본다`() {
        DependencyHealthController.requireUsableCluster("""{"cluster_name":"docker-cluster","status":"yellow","number_of_nodes":1}""")
        DependencyHealthController.requireUsableCluster("""{"status" : "green"}""")
        val failure = assertThrows(IllegalStateException::class.java) {
            DependencyHealthController.requireUsableCluster("""{"cluster_name":"docker-cluster","status":"red","timed_out":false}""")
        }
        assertEquals("cluster status red", failure.message)
    }
}
