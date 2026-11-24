package xyz.junproject.backend.progress.engine

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import xyz.junproject.backend.progress.infra.ProgressStore
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * 실제 PostgreSQL — 명세 15: DB 에 닿지 못한 직후(쉼)에는 DB 가 살아나도 닿아 보지 않고 곧바로 실패하며,
 * 쉼이 지나면 다시 닿아 성공한다. DB 의 죽음은 연결을 주지 않는 겉감으로 흉내 낸다(실제 풀은 그 안에 그대로).
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressStoreRecoveryEngineTest {
    private val schema = "progress_recovery_engine"
    private val administration = EngineSupport.administration()
    private val administrationJdbc = JdbcTemplate(administration)
    private val pool = EngineSupport.schemaPool(schema, size = 2)

    /** [down] 이면 연결을 주지 않는다. 연결을 준 · 거절한 횟수를 센다 */
    private class SwitchableDataSource(private val delegate: DataSource) : AbstractDataSource() {
        @Volatile var down = false
        @Volatile var connectionAttempts = 0
        override fun getConnection(): Connection {
            connectionAttempts++
            if (down) throw SQLException("Connection refused", "08001")
            return delegate.connection
        }
        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @AfterAll
    fun close() {
        administrationJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        pool.close()
        administration.close()
    }

    @Test
    fun `DB 가 살아나도 쉼 동안에는 닿아 보지 않고 쉼이 지나면 다시 닿아 성공한다`() {
        EngineSupport.recreateSchema(administrationJdbc, schema)
        val dataSource = SwitchableDataSource(pool)
        val clock = MovableClock(Instant.parse("2026-11-25T01:00:00Z"))
        val store = ProgressStore(JdbcTemplate(dataSource), clock, Duration.ofSeconds(5))
        store.createSchema()
        val created = store.createVisitor("r".repeat(64), LocalDate.of(2026, 11, 25), clock.now)

        dataSource.down = true
        assertThrows(DataAccessException::class.java) { store.findVisitor("r".repeat(64)) }
        val attemptsAfterFailure = dataSource.connectionAttempts

        dataSource.down = false                                          // DB 가 살아남
        clock.now = clock.now.plusSeconds(4)
        assertThrows(DataAccessException::class.java) { store.findVisitor("r".repeat(64)) }
        assertEquals(attemptsAfterFailure, dataSource.connectionAttempts, "쉼 동안에 DB 에 닿아 봤다")

        clock.now = clock.now.plusSeconds(2)                             // 실패부터 6초 — 쉼이 지남
        assertEquals(created, store.findVisitor("r".repeat(64)))
        assertTrue(dataSource.connectionAttempts > attemptsAfterFailure)
        assertNotNull(store.createVisitor("s".repeat(64), LocalDate.of(2026, 11, 25), clock.now))
    }
}
