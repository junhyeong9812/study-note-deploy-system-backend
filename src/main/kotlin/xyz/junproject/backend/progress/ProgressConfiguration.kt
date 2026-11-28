package xyz.junproject.backend.progress

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import xyz.junproject.backend.progress.infra.ProgressStore
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Clock

/**
 * 공부 기록 DB 의 조립 — 연결 풀은 직접 만든다(`spring-boot-starter-jdbc` 를 쓰지 않는다: 기본 헬스가 DB 에 매이면 DB 가 죽었을 때
 * 자리 전환이 막힌다, 명세 11.1). 뜰 때 DB 가 없어도 죽지 않고, 문맥이 닫힐 때 풀을 닫는다. 접속값(비밀번호)은 로그에 내지 않는다.
 */
@Configuration
@EnableScheduling
class ProgressConfiguration {
    @Volatile private var pool: HikariDataSource? = null

    /** 공부 기록 DB — 의존 서비스 헬스도 이것으로 DB 를 본다. 값은 환경 변수 STUDY_DB_URL · STUDY_DB_USER · STUDY_DB_PASSWORD */
    @Bean
    fun studyJdbc(
        @Value("\${study.db.url:jdbc:postgresql://postgres:5432/study}") url: String,
        @Value("\${study.db.user:study}") user: String,
        @Value("\${study.db.password:}") password: String,
    ): JdbcTemplate {
        val created = HikariDataSource(HikariConfig().apply {
            poolName = "study-db"
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            // DB 가 죽었을 때 요청이 오래 붙잡히지 않게 — 의존 서비스 헬스의 예산(3초) 안에 실패가 나온다
            connectionTimeout = 2_000
            validationTimeout = 1_000
            // 뜰 때 DB 가 아직 없어도 앱은 뜬다 — 첫 사용이 다시 닿는다
            initializationFailTimeout = -1
            connectionInitSql = "SET statement_timeout = 5000"
        })
        pool = created
        return JdbcTemplate(created)
    }

    @Bean
    fun progressStore(studyJdbc: JdbcTemplate) = ProgressStore(studyJdbc)

    @Bean
    fun studyProgressService(progressStore: ProgressStore, requestLog: RequestLog) =
        StudyProgressService(progressStore, Clock.systemUTC(), requestLog)

    /**
     * 뜬 직후 표를 미리 만든다(최선 노력) — 실패하면 로그만 남기고 첫 사용이 다시 만든다.
     * 뒤 스레드에서 돈다: DB 가 없을 때 연결 대기(2초)가 앱의 뜨기를 늦추지 않게.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun prepareProgressSchema(event: ApplicationReadyEvent) {
        val store = event.applicationContext.getBean(ProgressStore::class.java)
        val requestLog = event.applicationContext.getBean(RequestLog::class.java)
        Thread.ofVirtual().name("study-schema").start {
            val requestId = requestLog.newRequestId()
            try {
                store.createSchema()
                requestLog.log(requestId, "progress schema ready")
            } catch (error: DataAccessException) {
                requestLog.log(requestId, "progress schema failed — will retry on first use: ${error.javaClass.simpleName}", "warning")
            }
        }
    }

    /** 풀은 빈이 아니라 빈 메서드 안에서 만들어져 스프링이 닫아 주지 않는다 — 닫지 않으면 문맥마다 연결이 남는다 */
    @PreDestroy
    fun closePool() {
        pool?.close()
        pool = null
    }
}
