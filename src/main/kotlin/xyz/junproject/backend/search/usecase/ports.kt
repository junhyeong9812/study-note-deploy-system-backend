package xyz.junproject.backend.search.usecase

import xyz.junproject.backend.search.domain.RewriteOutcome

/** search 유즈케이스가 소유하는 포트들 — 구현은 shared/infra (DIP) */
interface QueryRewritePort {
    fun rewrite(requestId: String, query: String): RewriteOutcome
}

interface QueryEncoder {
    fun embedQuery(text: String): List<Double>
}

interface SearchIndexPort {
    fun search(bodyJson: String): Map<*, *>?
}
