package xyz.junproject.backend.indexing.usecase

/** indexing 유즈케이스가 소유하는 포트들 — 구현은 shared/infra (DIP) */
interface DocumentReader {
    fun readFile(path: String): String
}

interface TextEncoder {
    fun embed(texts: List<String>): List<List<Double>>
}

interface IndexStore {
    fun ensureIndex()
    fun deleteByPath(path: String)
    fun bulkUpsert(documents: List<Pair<String, String>>)
    fun countByPath(path: String): Long
    fun deleteWhereShaNot(commitSha: String): Long
}
