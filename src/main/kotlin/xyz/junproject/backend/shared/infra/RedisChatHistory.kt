package xyz.junproject.backend.shared.infra

import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import xyz.junproject.backend.chat.usecase.ChatHistoryPort
import xyz.junproject.backend.chat.usecase.ChatService

/**
 * 대화 이력(Redis `chat:{session}:{문서 경로}` — 키 꼴은 [ChatService.historyKey])을 세션 단위로 보고 지운다(명세 12 · 18).
 * 키는 `KEYS` 가 아니라 `SCAN` 으로 찾는다(한 번에 Redis 를 붙잡지 않게). 꼴이 틀린 세션 값은 받지 않는다([ChatService.historyKeyPattern]).
 * Redis 오류는 DataAccessException 으로 던진다.
 */
@Component
class RedisChatHistory(private val redis: StringRedisTemplate) : ChatHistoryPort {
    private companion object {
        /** SCAN 한 번에 훑는 키의 수 · 한 번에 지우는 키의 수 */
        const val BATCH = 500
    }

    private fun <T> withHistoryKeys(sessionId: String, work: (Sequence<String>) -> T): T {
        val options = ScanOptions.scanOptions().match(ChatService.historyKeyPattern(sessionId)).count(BATCH.toLong()).build()
        return try {
            redis.scan(options).use { cursor -> work(cursor.asSequence()) }
        } catch (error: DataAccessException) {
            throw error
        } catch (error: RuntimeException) {
            // 커서를 넘기다 난 드라이버의 예외도 저장소 오류로 — 부르는 쪽이 "지우지 못함"으로 답하게
            throw RedisSystemException("chat history scan failed", error)
        }
    }

    override fun hasHistory(sessionId: String): Boolean = withHistoryKeys(sessionId) { keys -> keys.any() }

    override fun deleteHistory(sessionId: String) {
        withHistoryKeys(sessionId) { keys -> keys.chunked(BATCH).forEach { batch -> redis.delete(batch) } }
    }
}
