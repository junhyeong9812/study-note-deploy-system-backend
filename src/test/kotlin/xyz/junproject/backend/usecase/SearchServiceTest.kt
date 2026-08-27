package xyz.junproject.backend.usecase

import xyz.junproject.backend.domain.RewriteOutcome
import xyz.junproject.backend.domain.SearchHit
import xyz.junproject.backend.infra.LlmClient

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchServiceTest {
    private val service = SearchService(mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), tools.jackson.databind.ObjectMapper())

    private fun hit(path: String, score: Double, chunkNo: Int = 0) =
        SearchHit(path, chunkNo, "h", "s", "summary", score)

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
    fun `같은 파일의 다른 청크는 합쳐지지 않는다 - 병합 키는 path#chunk_no`() {
        val bm25 = listOf(hit("a.md", 2.0, chunkNo = 1), hit("a.md", 1.0, chunkNo = 2))
        val merged = service.rrfMerge(listOf(bm25, emptyList()), size = 5)
        assertEquals(2, merged.size)                            // 리뷰 B1 — heading 키였다면 1개로 접힘
    }

    @Test
    fun `임베딩 장애 시 BM25 단독으로 응답한다 - dense_used=false`() {
        val embedding = mockk<xyz.junproject.backend.infra.EmbeddingClient>()
        io.mockk.every { embedding.embedQuery(any()) } throws RuntimeException("down")
        val es = mockk<xyz.junproject.backend.infra.EsClient>()
        io.mockk.every { es.search(any()) } returns mapOf("hits" to mapOf("hits" to listOf(
            mapOf("_score" to 1.0, "_source" to mapOf(
                "path" to "a.md", "chunk_no" to 0, "heading" to "h",
                "content" to "c", "doc_kind" to "summary")))))
        val llm = mockk<LlmClient>()
        io.mockk.every { llm.rewrite(any(), any()) } returns RewriteOutcome(used = false)
        val fallbackService = SearchService(embedding, es, llm,
            mockk(relaxed = true), tools.jackson.databind.ObjectMapper())
        val result = fallbackService.search("req-1", "질의", null, null, 5)
        assertEquals(false, result["dense_used"])               // 리뷰 B4 — search() 레벨 검증
        assertEquals(1, (result["results"] as List<*>).size)
    }
}
