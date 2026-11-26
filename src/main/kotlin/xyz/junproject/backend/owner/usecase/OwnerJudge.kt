package xyz.junproject.backend.owner.usecase

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import xyz.junproject.backend.owner.api.OwnerCookie
import xyz.junproject.backend.owner.domain.OwnerMark
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

    init {
        if (!OwnerMark.usableSecret(secret)) {
            logger.warn("$SECRET_VARIABLE 이 없거나 ${OwnerMark.MIN_SECRET_BYTES}바이트보다 짧다 — 누구도 주인이 아니다(Claude 로 가는 경로는 모두 거절)")
        }
    }

    /** 요청의 쿠키 가운데 이름이 정확히 `study_owner` 인 것만 본다 */
    fun judge(requestId: String, request: HttpServletRequest): OwnerVerdict {
        val values = request.cookies?.filter { cookie -> cookie.name == OwnerCookie.NAME }?.map { cookie -> cookie.value } ?: emptyList()
        val verdict = OwnerMark.verify(values, secret, clock.instant())
        requestLog.log(requestId, if (verdict.owner) "owner=true" else "owner=false reason=${verdict.reason?.word}")
        return verdict
    }

    companion object {
        const val SECRET_VARIABLE = "OWNER_COOKIE_SECRET"
        private val logger = LoggerFactory.getLogger(OwnerJudge::class.java)
    }
}
