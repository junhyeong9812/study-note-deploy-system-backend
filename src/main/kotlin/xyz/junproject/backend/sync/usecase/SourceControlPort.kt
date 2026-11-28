package xyz.junproject.backend.sync.usecase

/** sync가 필요로 하는 소스 저장소 능력 — 구현은 shared/infra/GitRepository (DIP: 서비스가 인터페이스 소유) */
interface SourceControlPort {
    fun syncToRemoteHead(): String
    fun changedMarkdown(prevSha: String, headSha: String): List<Pair<Char, String>>
    fun allMarkdown(): List<String>
    /** 내용 저장소 이력의 상태 — 마지막 동기화가 알아낸 값을 돌려준다(물을 때 git 을 띄우지 않는다). 동기화 전에는 [HistoryState.UNKNOWN] */
    fun historyState(): HistoryState
}

/**
 * [shallow]: 저장소가 얕은가 — true · false · null(모름: 동기화 전이거나 얕은지 묻는 명령이 실패).
 * [problem]: 마지막 동기화에서 깊게 받기 · 얕은지 묻기가 실패한 문구(git 의 출력 앞부분) — 실패가 없으면 null.
 */
data class HistoryState(val shallow: Boolean?, val problem: String?) {
    companion object {
        val UNKNOWN = HistoryState(shallow = null, problem = null)
    }
}

class ShaUnresolvableException(message: String) : RuntimeException(message)
