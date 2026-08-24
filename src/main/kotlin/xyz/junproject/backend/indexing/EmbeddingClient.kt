package xyz.junproject.backend.indexing

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** BGE-M3 dense 임베딩 (sparse는 1차 제외 — es-index.md D4 [구현 검증] #2). */
@Component
class EmbeddingClient {
    private val client = RestClient.create(System.getenv("EMBEDDING_URL") ?: "http://embedding:8080")

    @Suppress("UNCHECKED_CAST")
    fun embed(texts: List<String>): List<List<Double>> {
        if (texts.isEmpty()) return emptyList()
        val response = client.post().uri("/embed")
            .body(mapOf("texts" to texts, "dense" to true, "sparse" to false))
            .retrieve().body(Map::class.java) ?: error("embedding: empty response")
        return response["dense"] as? List<List<Double>> ?: error("embedding: no dense field")
    }
}
