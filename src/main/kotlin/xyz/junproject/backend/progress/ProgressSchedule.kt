package xyz.junproject.backend.progress

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.shared.infra.RequestLog

/**
 * 하루 한 번 마지막 방문부터 1년이 지난 방문자의 기록을 지운다(명세 2.2 · 15) — 묶음(500)으로 되풀이하고 한 실행 50,000 명에서 멈춘다.
 * 결과(지운 수 · `remaining=<남은 수>` · 실패)는 서비스가 로그로 남기고 던지지 않는다. 남은 것은 다음 날의 실행이 이어 지운다.
 */
@Component
class ProgressSchedule(private val studyProgressService: StudyProgressService, private val requestLog: RequestLog) {
    // 두 자리(blue · green)가 함께 돌아도 결과가 같다 — 저장소가 잠금으로 줄 세우고, 뒤에 선 쪽은 이미 지워진 줄을 다시 지우지 않는다
    @Scheduled(cron = "\${study.progress.retention-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun purgeInactiveVisitors() {
        studyProgressService.purgeInactiveVisitors(requestLog.newRequestId())
    }
}
