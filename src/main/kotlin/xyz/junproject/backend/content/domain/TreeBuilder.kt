package xyz.junproject.backend.content.domain

import xyz.junproject.backend.indexing.domain.DocClassifier
import xyz.junproject.backend.indexing.domain.DocMeta

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 경로 목록 → 중첩 트리 (순수 함수). 모델: 리프 폴더 = 주제(subject),
 * 그 안은 단일 문서(post) 또는 1/2/3 챕터 문서들. (#12 — 사용자 결정 2026-08-27)
 */
object TreeBuilder {

    data class DocRef(
        val path: String,
        @get:JsonProperty("doc_kind") val docKind: String,   // API 계약은 snake_case 통일
        val form: String,
    )
    data class Node(
        val name: String,
        val path: String,                       // repo 상대 폴더 경로 ("" = 루트)
        val docs: List<DocRef>,                 // 이 폴더 직속 문서
        val children: List<Node>,               // 하위 폴더 (이름순)
    ) {
        @get:JsonProperty("is_subject")
        val isSubject: Boolean get() = docs.isNotEmpty() && children.isEmpty()   // 리프 폴더 = 주제
    }

    fun build(paths: List<String>): Node {
        val metas = paths.map { DocClassifier.classify(it) }
        return buildNode(name = "", path = "", metas = metas, depth = 0)
    }

    private fun buildNode(name: String, path: String, metas: List<DocMeta>, depth: Int): Node {
        val (direct, deeper) = metas.partition { it.topicPath.size == depth }
        val docs = direct.sortedBy { it.path }
            .map { DocRef(it.path, it.docKind, it.form) }
        val children = deeper.groupBy { it.topicPath[depth] }
            .toSortedMap()
            .map { (childName, childMetas) ->
                val childPath = if (path.isEmpty()) childName else "$path/$childName"
                buildNode(childName, childPath, childMetas, depth + 1)
            }
        return Node(name, path, docs, children)
    }
}
