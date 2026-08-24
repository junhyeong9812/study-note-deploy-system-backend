package xyz.junproject.backend.search

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchServiceTest {
    private val service = SearchService(mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), tools.jackson.databind.ObjectMapper())

    private fun hit(path: String, score: Double) = SearchHit(path, "h", "s", "summary", score)

    @Test
    fun `RRF - 양쪽 랭킹에 다 있는 문서가 위로 온다`() {
        val bm25 = listOf(hit("a.md", 10.0), hit("b.md", 5.0), hit("c.md", 1.0))
        val knn = listOf(hit("c.md", 0.9), hit("a.md", 0.8))
        val merged = service.rrfMerge(listOf(bm25, knn), size = 3)
        assertEquals("a.md", merged[0].path)          // 1위+2위 — 양쪽 상위
        assertTrue(merged.any { it.path == "c.md" })
    }

    @Test
    fun `RRF - 점수 스케일 무관, 순위만 반영`() {
        val big = listOf(hit("x.md", 9999.0))
        val small = listOf(hit("y.md", 0.001))
        val merged = service.rrfMerge(listOf(big, small), size = 2)
        assertEquals(2, merged.size)
        assertEquals(merged[0].score, merged[1].score, 1e-9)   // 각자 1위 → 동점
    }

    @Test
    fun `한쪽 랭킹이 비어도 병합된다 - kNN 실패 폴백`() {
        val merged = service.rrfMerge(listOf(listOf(hit("a.md", 1.0)), emptyList()), size = 5)
        assertEquals(1, merged.size)
    }
}
