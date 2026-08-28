package xyz.junproject.backend.content.usecase

import org.springframework.stereotype.Service
import xyz.junproject.backend.content.domain.TreeBuilder
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 트리 캐시 — sync 성공 시 재생성(트리는 그때만 변한다), commit_sha 태깅.
 * 캐시가 비어 있으면(재시작 직후) 첫 요청 시 생성.
 */
@Service
class TreeService(private val git: NoteSourcePort, private val requestLog: RequestLog) {

    data class CachedTree(val commitSha: String, val tree: TreeBuilder.Node)

    @Volatile private var cached: CachedTree? = null

    fun rebuild(requestId: String, commitSha: String) {
        val tree = TreeBuilder.build(git.allMarkdown())
        cached = CachedTree(commitSha, tree)
        requestLog.log(requestId, "tree rebuilt sha=$commitSha")
    }

    /** 캐시 반환 — 없으면 현재 clone 기준으로 생성 (sha는 clone의 HEAD) */
    fun get(requestId: String): CachedTree {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val headSha = git.currentHead()
            val tree = TreeBuilder.build(git.allMarkdown())
            return CachedTree(headSha, tree).also {
                cached = it
                requestLog.log(requestId, "tree built lazily sha=$headSha")
            }
        }
    }
}
