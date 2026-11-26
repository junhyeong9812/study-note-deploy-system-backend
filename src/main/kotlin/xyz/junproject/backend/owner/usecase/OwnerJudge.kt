package xyz.junproject.backend.owner.usecase

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import xyz.junproject.backend.owner.domain.OwnerVerdict
import xyz.junproject.backend.shared.infra.RequestLog
import java.time.Clock

/**
 * 요청이 주인의 것인가 — 서버가 쿠키 `study_owner` 하나로만 판정한다(머리 · 질의 · 몸의 칸 · 다른 쿠키를 보지 않는다).
 * 판정은 요청 로그에 `owner=true` 또는 `owner=false reason=<낱말>` 로 남긴다(쿠키의 값 · 서명은 남기지 않는다).
 * 열쇠를 쓸 수 없으면 만들 때 WARN 한 줄(`OWNER_COOKIE_SECRET` 의 이름만 — 값은 싣지 않는다).
 */
@Component
class OwnerJudge(
    private val secret: String?,
    private val clock: Clock,
    private val requestLog: RequestLog,
) {
    @Autowired
    constructor(requestLog: RequestLog) : this(System.getenv(SECRET_VARIABLE), Clock.systemUTC(), requestLog)

    fun judge(requestId: String, request: HttpServletRequest): OwnerVerdict = TODO("2a")

    companion object {
        const val SECRET_VARIABLE = "OWNER_COOKIE_SECRET"
    }
}
