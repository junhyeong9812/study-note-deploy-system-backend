package xyz.junproject.backend.infra

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/** BGE-M3 dense 임베딩 (sparse는 1차 제외 — es-index.md D4 [구현 검증]). */
@Component
class EmbeddingClient {
    private val baseUrl = System.getenv("EMBEDDING_URL") ?: "http://embedding:8080"

    private fun clientWith(readTimeout: Duration): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(readTimeout)
        })
        .build()

    private val batchClient = clientWith(Duration.ofMinutes(3))   // 색인 배치 — 대형 파일 대비
    private val queryClient = clientWith(Duration.ofSeconds(5))   // 검색 질의 1건 — 사용자 경로 (B4)

    fun embed(texts: List<String>): List<List<Double>> = call(batchClient, texts)

    /** 검색어 1건 — 느리면 빨리 포기하고 BM25 폴백 (호출부 try/catch 전제) */
    fun embedQuery(text: String): List<Double> =
        call(queryClient, listOf(text)).firstOrNull() ?: error("embedding: empty query vector")

    @Suppress("UNCHECKED_CAST")
    private fun call(client: RestClient, texts: List<String>): List<List<Double>> {
        if (texts.isEmpty()) return emptyList()
        val response = client.post().uri("/embed")
            .body(mapOf("texts" to texts, "dense" to true, "sparse" to false))
            .retrieve().body(Map::class.java) ?: error("embedding: empty response")
        return response["dense"] as? List<List<Double>> ?: error("embedding: no dense field")
    }
}
