package xyz.junproject.backend.progress

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import xyz.junproject.backend.progress.usecase.StudyProgressService
import xyz.junproject.backend.shared.infra.RequestLog

/** 하루 한 번 마지막 방문부터 1년이 지난 방문자의 기록을 지운다(명세 2.2) — 실패는 서비스가 로그와 결과로 남기고 던지지 않는다 */
@Component
class ProgressSchedule(private val studyProgressService: StudyProgressService, private val requestLog: RequestLog) {
    // 두 자리(blue · green)가 함께 돌아도 결과가 같다 — 저장소가 잠금으로 줄 세우고, 뒤에 선 쪽은 이미 지워진 줄을 다시 지우지 않는다
    @Scheduled(cron = "\${study.progress.retention-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun purgeInactiveVisitors() {
        studyProgressService.purgeInactiveVisitors(requestLog.newRequestId())
    }
}
