package xyz.junproject.backend.chat.usecase

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.content.usecase.NoteSourcePort
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Duration

/**
 * 채팅 오케스트레이션 (chat-design):
 * 대화 상태의 정본 = Redis(chat:{session}:{path}, TTL 7일) · 문서의 정본 = git(경로만 받음)
 * 컨텍스트 = 문서 원문(system) + 최근 이력 N턴 + 새 질문
 */
@Service
class ChatService(
    private val source: NoteSourcePort,
    private val chatStream: ChatStreamPort,
    private val escalate: EscalatePort,
    private val redis: StringRedisTemplate,
    private val requestLog: RequestLog,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val HISTORY_TURNS = 10          // 모델에 싣는 최근 항목 수 [구현 검증]
        const val DOC_CONTEXT_CHARS = 12_000  // 문서 컨텍스트 상한 [구현 검증]
        val TTL: Duration = Duration.ofDays(7)
    }

    private fun key(sessionId: String, docPath: String) = "chat:$sessionId:$docPath"

    data class ChatMessage(val role: String, val content: String)

    fun history(sessionId: String, docPath: String): List<ChatMessage> =
        redis.opsForList().range(key(sessionId, docPath), 0, -1).orEmpty()
            .map { objectMapper.readValue(it, ChatMessage::class.java) }

    private fun append(sessionId: String, docPath: String, message: ChatMessage) {
        val redisKey = key(sessionId, docPath)
        redis.opsForList().rightPush(redisKey, objectMapper.writeValueAsString(message))
        redis.expire(redisKey, TTL)           // 대화가 이어지는 동안 수명 연장
    }

    private fun buildMessages(docPath: String, historyItems: List<ChatMessage>, question: String):
            List<Map<String, String>> {
        val document = try { source.readFile(docPath).take(DOC_CONTEXT_CHARS) } catch (_: Exception) { "" }
        val system = "너는 공부 노트 위키의 문서 도우미다. 아래 문서를 근거로 한국어로 답한다. " +
            "문서에 없는 내용은 추측하지 말고 문서에 없다고 말한다.\n\n[문서: $docPath]\n$document"
        return buildList {
            add(mapOf("role" to "system", "content" to system))
            historyItems.takeLast(HISTORY_TURNS).forEach {
                add(mapOf("role" to it.role, "content" to it.content))
            }
            add(mapOf("role" to "user", "content" to question))
        }
    }

    /** 스트리밍 응답 — 토큰을 onToken으로 흘리고, 완료 후 이력 기록 */
    fun chat(requestId: String, sessionId: String, docPath: String, question: String,
             onToken: (String) -> Unit) {
        val messages = buildMessages(docPath, history(sessionId, docPath), question)
        val answer = StringBuilder()
        chatStream.stream(requestId, messages) { token ->
            answer.append(token)
            onToken(token)
        }
        append(sessionId, docPath, ChatMessage("user", question))
        append(sessionId, docPath, ChatMessage("assistant", answer.toString()))
        requestLog.log(requestId, "chat ok $docPath q=${question.take(30)} answer=${answer.length}chars")
    }

    /** 에스컬레이션 — Claude 브리지(수동 트리거). 전체 문서+이력+질문을 프롬프트로 */
    fun escalateAnswer(requestId: String, sessionId: String, docPath: String, question: String): String {
        check(escalate.available) { "bridge unavailable" }
        val document = try { source.readFile(docPath) } catch (_: Exception) { "" }
        val historyText = history(sessionId, docPath).takeLast(HISTORY_TURNS)
            .joinToString("\n") { "${it.role}: ${it.content.take(500)}" }
        val prompt = "다음 공부 노트 문서에 대한 질문에 정확하고 간결하게 한국어로 답해줘.\n" +
            "[문서: $docPath]\n$document\n\n[이전 대화]\n$historyText\n\n[질문]\n$question"
        val answer = escalate.ask(requestId, prompt)
        append(sessionId, docPath, ChatMessage("user", question))
        append(sessionId, docPath, ChatMessage("assistant", answer))
        requestLog.log(requestId, "chat escalate ok $docPath answer=${answer.length}chars")
        return answer
    }
}
