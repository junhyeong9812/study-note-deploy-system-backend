package xyz.junproject.backend.shared.infra

import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalatePort
import xyz.junproject.backend.chat.usecase.EscalateResult
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Claude 실행 자리를 부른다(명세 §20.1 · §21) — `POST <runnerUrl>/runs` · 머리글 `X-Claude-Runner-Secret` · 몸은 `Content-Length` 가 있는 바이트
 * `{"purpose":"chat","prompt":…,"request_id":…}` · 리다이렉트를 따라가지 않는다 · 연결 [connectTimeout] · 받는 것 전체가 부르는 쪽의 기한까지 · 재시도 없음.
 * 결과는 값([EscalateResult]) — 상태 코드만으로 가르지 않고 `error.code` 로.
 * 설정(`CLAUDE_RUNNER_URL` · `CLAUDE_RUNNER_SECRET`)이 비면 [available] 이 거짓이고 부르지 않는다.
 */
class ClaudeRunnerClient(
    private val runnerUrl: String,
    private val secret: String,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val connectTimeout: Duration = Duration.ofSeconds(3),
) : EscalatePort {
    override val available: Boolean get() = TODO("5a")
    override val autoEscalation: Boolean get() = TODO("5a")
    override fun ask(requestId: String, prompt: String, deadline: Instant): EscalateResult = TODO("5a")

    companion object {
        const val SECRET_HEADER = "X-Claude-Runner-Secret"
        const val PURPOSE = "chat"
    }
}
