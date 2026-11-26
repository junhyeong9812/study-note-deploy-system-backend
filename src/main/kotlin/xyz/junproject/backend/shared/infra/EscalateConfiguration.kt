package xyz.junproject.backend.shared.infra

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalatePort

/**
 * Claude 쪽의 구현을 고르는 한 곳(명세 §20.1 · §21-5) — 주입되는 [EscalatePort] 는 하나다.
 * 실행 자리(`CLAUDE_RUNNER_URL` · `CLAUDE_RUNNER_SECRET`)가 설정돼 있으면 그것(옛 통로가 있어도 이긴다), 아니면 옛 통로(`CLAUDE_BRIDGE_URL` · `BRIDGE_SECRET` — 단추만).
 * 둘 다 없으면 옛 통로의 빈 설정 — 부를 곳이 없다.
 */
@Configuration
class EscalateConfiguration {
    private val logger = LoggerFactory.getLogger(EscalateConfiguration::class.java)

    @Bean
    fun escalatePort(
        objectMapper: ObjectMapper,
        @Value("\${CLAUDE_RUNNER_URL:}") runnerUrl: String,
        @Value("\${CLAUDE_RUNNER_SECRET:}") runnerSecret: String,
        @Value("\${CLAUDE_BRIDGE_URL:}") bridgeUrl: String,
        @Value("\${BRIDGE_SECRET:}") bridgeSecret: String,
    ): EscalatePort {
        val runner = ClaudeRunnerClient(runnerUrl, runnerSecret, objectMapper)
        val chosen: EscalatePort = if (runner.available) runner else ClaudeBridgeClient(objectMapper, bridgeUrl, bridgeSecret)
        // 어느 쪽인지만 — 주소 · 비밀값은 싣지 않는다
        val name = when {
            chosen is ClaudeRunnerClient -> "runner"
            chosen.available -> "bridge"
            else -> "none"
        }
        logger.info("claude escalation: $name (auto=${chosen.autoEscalation})")
        return chosen
    }
}
