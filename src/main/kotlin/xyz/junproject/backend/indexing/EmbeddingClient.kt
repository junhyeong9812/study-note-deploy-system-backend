package xyz.junproject.backend.indexing

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/** BGE-M3 dense 임베딩 (sparse는 1차 제외 — es-index.md D4 [구현 검증] #2). */
@Component
class EmbeddingClient {
    private val client = RestClient.builder()
        .baseUrl(System.getenv("EMBEDDING_URL") ?: "http://embedding:8080")
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofMinutes(3))   // 대형 파일(58KB→다청크) 배치 대비
        })
        .build()

    @Suppress("UNCHECKED_CAST")
    fun embed(texts: List<String>): List<List<Double>> {
        if (texts.isEmpty()) return emptyList()
        val response = client.post().uri("/embed")
            .body(mapOf("texts" to texts, "dense" to true, "sparse" to false))
            .retrieve().body(Map::class.java) ?: error("embedding: empty response")
        return response["dense"] as? List<List<Double>> ?: error("embedding: no dense field")
    }
}
