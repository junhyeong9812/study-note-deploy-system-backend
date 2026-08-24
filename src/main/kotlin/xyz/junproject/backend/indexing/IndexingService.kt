package xyz.junproject.backend.indexing

import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
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
    companion object {
        const val MAX_EMBED_CHARS = 9000   // 청크 상한(8KB)보다 크게 — 절단은 사실상 발생하지 않음(발생 시 warning) [구현 검증]
        const val PROGRESS_EVERY = 50
    }

    fun indexPaths(requestId: String, changes: List<Pair<Char, String>>, commitSha: String,
                   onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        es.ensureIndex()
        var indexedChunks = 0
        changes.forEachIndexed { fileIndex, (state, path) ->
            es.deleteByPath(path)                       // 삭제·개정 공통 선행 (고아 방지)
            if (state == 'D') {
                requestLog.log(requestId, "index delete $path")
                return@forEachIndexed
            }
            val doc = Chunker.chunk(git.readFile(path))
            if (doc.chunks.isEmpty()) return@forEachIndexed
            val meta = DocClassifier.classify(path)
            val title = doc.title.ifBlank {            // D4: h1 없으면 파일명 폴백
                path.substringAfterLast("/").removeSuffix(".md")
            }
            val embedInputs = doc.chunks.map { chunk ->
                val text = "${chunk.heading}\n${chunk.content}"
                if (text.length > MAX_EMBED_CHARS) {
                    requestLog.log(requestId,
                        "embed truncate $path: ${text.length}->${MAX_EMBED_CHARS}chars", "warning")
                }
                text.take(MAX_EMBED_CHARS)
            }
            val vectors = embedding.embed(embedInputs)
            check(vectors.size == doc.chunks.size) {   // 응답 개수 검증 (silent 부분 응답 차단)
                "임베딩 개수 불일치 $path: vectors=${vectors.size} chunks=${doc.chunks.size}"
            }
            check(vectors.first().size == 1024) {      // 매핑 dims와 일치 (감사 지적)
                "임베딩 차원 불일치 $path: ${vectors.first().size}"
            }
            val documents = doc.chunks.mapIndexed { chunkNo, chunk ->
                val source = objectMapper.writeValueAsString(mapOf<String, Any?>(
                    "path" to meta.path, "topic" to meta.topic, "topic_path" to meta.topicPath,
                    "subject" to meta.subject, "depth" to meta.depth,
                    "form" to meta.form, "doc_kind" to meta.docKind,
                    "title" to title,
                    "heading" to chunk.heading, "content" to chunk.content,
                    "chunk_no" to chunkNo, "dense" to vectors[chunkNo],
                    "commit_sha" to commitSha, "updated_at" to Instant.now().toString(),
                ))
                "$path#$chunkNo" to source
            }
            es.bulkUpsert(documents)
            // record-level 검증 — "에러 없이 돌았다 ≠ 완료" (silent failure 차단)
            val count = es.countByPath(path)
            check(count == doc.chunks.size.toLong()) { "count 불일치 $path: es=$count chunks=${doc.chunks.size}" }
            indexedChunks += doc.chunks.size
            if ((fileIndex + 1) % PROGRESS_EVERY == 0) {
                requestLog.log(requestId, "index progress ${fileIndex + 1}/${changes.size}")
                onProgress(fileIndex + 1, changes.size)
            }
        }
        return indexedChunks
    }

    /** full 색인 후 고아 정리 — 이번 SHA로 안 찍힌 문서 = 저장소에 더 없는 문서 (B7) */
    fun deleteStale(requestId: String, commitSha: String) {
        val deleted = es.deleteWhereShaNot(commitSha)
        if (deleted > 0) requestLog.log(requestId, "index stale cleanup: $deleted docs (sha!=$commitSha)")
    }
}
