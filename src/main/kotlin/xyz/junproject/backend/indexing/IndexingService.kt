package xyz.junproject.backend.indexing

import tools.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import xyz.junproject.backend.common.RequestLog
import xyz.junproject.backend.sync.GitRepository
import java.time.Instant

/** 변경 파일 → 청킹 → 임베딩 → ES 색인. record-level 검증 포함 (es-index.md D5). */
@Service
class IndexingService(
    private val git: GitRepository,
    private val embedding: EmbeddingClient,
    private val es: EsClient,
    private val requestLog: RequestLog,
    private val objectMapper: ObjectMapper,
) {
    fun indexPaths(requestId: String, changes: List<Pair<Char, String>>, commitSha: String): Int {
        es.ensureIndex()
        var indexedChunks = 0
        for ((state, path) in changes) {
            es.deleteByPath(path)                       // 삭제·개정 공통 선행 (고아 방지)
            if (state == 'D') {
                requestLog.log(requestId, "index delete $path")
                continue
            }
            val meta = DocClassifier.classify(path)
            val chunks = Chunker.chunk(git.readFile(path))
            if (chunks.isEmpty()) continue
            val vectors = embedding.embed(chunks.map { "${it.heading}\n${it.content}".take(6000) })
            val documents = chunks.mapIndexed { chunkNo, chunk ->
                val source = objectMapper.writeValueAsString(mapOf<String, Any?>(
                    "path" to meta.path, "topic" to meta.topic, "topic_path" to meta.topicPath,
                    "subject" to meta.subject, "depth" to meta.depth,
                    "form" to meta.form, "doc_kind" to meta.docKind,
                    "title" to (chunks.first().heading.substringBefore(" > ")),
                    "heading" to chunk.heading, "content" to chunk.content,
                    "chunk_no" to chunkNo, "dense" to vectors[chunkNo],
                    "commit_sha" to commitSha, "updated_at" to Instant.now().toString(),
                ))
                "$path#$chunkNo" to source
            }
            es.bulkUpsert(documents)
            // record-level 검증 — "에러 없이 돌았다 ≠ 완료" (silent failure 차단)
            val count = es.countByPath(path)
            check(count == chunks.size.toLong()) { "count 불일치 $path: es=$count chunks=${chunks.size}" }
            indexedChunks += chunks.size
        }
        return indexedChunks
    }
}
