package xyz.junproject.backend.progress.engine

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 실제 엔진 테스트의 접속 — scripts/engine-test.sh 가 환경변수로 넘긴다.
 * 값이 없으면 건너뛰지 않고 실패한다(건너뛴 테스트는 통과로 보이기 때문).
 */
object EngineSupport {
    private fun environment(name: String) = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
        "$name 이 없다 — scripts/engine-test.sh 로 돌린다"
    }

    val databaseUrl get() = environment("BACKEND_TEST_DB_URL")            // jdbc:postgresql://host:5432/study
    val databaseUser get() = environment("BACKEND_TEST_DB_USER")
    val databasePassword get() = environment("BACKEND_TEST_DB_PASSWORD")

    /** 기본 스키마의 풀 — 스키마를 만들고 지우는 관리용 */
    fun administration(size: Int = 1) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl; username = databaseUser; password = databasePassword; maximumPoolSize = size
    })

    /** 전용 스키마의 풀 — 사례끼리 섞이지 않게. 한 풀이 한 "자리"(blue · green)를 흉내 낸다 */
    fun schemaPool(schema: String, size: Int = 4) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "$databaseUrl?currentSchema=$schema"; username = databaseUser; password = databasePassword; maximumPoolSize = size
    })

    fun recreateSchema(administration: JdbcTemplate, schema: String) {
        administration.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        administration.execute("CREATE SCHEMA $schema")
    }
}
