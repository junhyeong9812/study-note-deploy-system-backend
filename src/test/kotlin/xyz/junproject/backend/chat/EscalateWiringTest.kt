package xyz.junproject.backend.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import xyz.junproject.backend.chat.usecase.EscalatePort
import xyz.junproject.backend.shared.infra.ClaudeBridgeClient
import xyz.junproject.backend.shared.infra.ClaudeRunnerClient

/**
 * 어느 Claude 쪽이 주입되는가(명세 §20.1 · §21-5) — 앱이 뜨고, [EscalatePort] 의 빈은 **하나**다.
 * 설정(스프링의 설정으로 읽는다 — 환경 변수 이름 그대로): `CLAUDE_RUNNER_URL` · `CLAUDE_RUNNER_SECRET`(실행 자리) · `CLAUDE_BRIDGE_URL`(옛 통로).
 * - 실행 자리가 설정돼 있으면 그것([ClaudeRunnerClient] — 부를 곳 있음 · 자동 넘김 가능) — 옛 통로도 설정돼 있어도 실행 자리가 이긴다.
 * - 아니면 옛 통로([ClaudeBridgeClient] — 부를 곳 있음 · 자동 넘김 불가).
 * - 둘 다 없으면 부를 곳이 없다(어느 구현이든 available · autoEscalation 이 거짓).
 * DB · Redis · ES 는 없다(ApplicationWithoutDatabaseTest 와 같은 설정).
 */
private const val DEAD_DATABASE = "study.db.url=jdbc:postgresql://127.0.0.1:1/study"

private fun assertSingle(context: ApplicationContext): EscalatePort {
    val beans = context.getBeansOfType(EscalatePort::class.java)
    assertEquals(1, beans.size, "EscalatePort 의 빈이 하나가 아니다: ${beans.keys}")
    return beans.values.single()
}

@SpringBootTest(properties = [DEAD_DATABASE, "study.db.user=study", "study.db.password=unused", "management.health.redis.enabled=false",
    "CLAUDE_RUNNER_URL=", "CLAUDE_RUNNER_SECRET=", "CLAUDE_BRIDGE_URL="])
@DirtiesContext
class EscalateWiringWithoutSettingsTest {
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `설정이 없으면 부를 곳이 없다`() {
        val port = assertSingle(context)
        assertFalse(port.available)
        assertFalse(port.autoEscalation)
    }
}

@SpringBootTest(properties = [DEAD_DATABASE, "study.db.user=study", "study.db.password=unused", "management.health.redis.enabled=false",
    "CLAUDE_RUNNER_URL=http://127.0.0.1:1", "CLAUDE_RUNNER_SECRET=runner-secret-for-wiring-test", "CLAUDE_BRIDGE_URL="])
@DirtiesContext
class EscalateWiringRunnerTest {
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `실행 자리만 설정돼 있으면 실행 자리를 쓰고 자동 넘김을 할 수 있다`() {
        val port = assertSingle(context)
        assertTrue(port is ClaudeRunnerClient, port.javaClass.name)
        assertTrue(port.available)
        assertTrue(port.autoEscalation)
    }
}

@SpringBootTest(properties = [DEAD_DATABASE, "study.db.user=study", "study.db.password=unused", "management.health.redis.enabled=false",
    "CLAUDE_RUNNER_URL=", "CLAUDE_RUNNER_SECRET=", "CLAUDE_BRIDGE_URL=http://127.0.0.1:2"])
@DirtiesContext
class EscalateWiringBridgeTest {
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `옛 통로만 설정돼 있으면 옛 통로를 쓰고 자동 넘김은 하지 않는다`() {
        val port = assertSingle(context)
        assertTrue(port is ClaudeBridgeClient, port.javaClass.name)
        assertTrue(port.available)
        assertFalse(port.autoEscalation)
    }
}

@SpringBootTest(properties = [DEAD_DATABASE, "study.db.user=study", "study.db.password=unused", "management.health.redis.enabled=false",
    "CLAUDE_RUNNER_URL=http://127.0.0.1:1", "CLAUDE_RUNNER_SECRET=runner-secret-for-wiring-test", "CLAUDE_BRIDGE_URL=http://127.0.0.1:2"])
@DirtiesContext
class EscalateWiringBothTest {
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `둘 다 설정돼 있으면 실행 자리가 이긴다`() {
        val port = assertSingle(context)
        assertTrue(port is ClaudeRunnerClient, port.javaClass.name)
        assertTrue(port.autoEscalation)
    }
}
