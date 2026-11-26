package xyz.junproject.backend.shared.api

import xyz.junproject.backend.shared.infra.RequestLog

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 어떤 예외도 봉투 밖으로 새지 않는다 — Spring 기본 오류 JSON 차단 (B6, 규약: llm과 동일). */
@RestControllerAdvice
class GlobalErrorHandler(private val requestLog: RequestLog) {

    @ExceptionHandler(
        MissingServletRequestParameterException::class, MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class,
    )
    fun missingInput(error: Exception): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Envelope.fail("invalid_request", detail = error.message?.take(200)))

    /**
     * 로그의 ID 는 그 요청의 추적 ID — ① 그 요청에서 정한 값(acceptOrIssue) ② 없으면 꼴이 맞는 들어온 머리글 ③ 아니면 새로 만든다
     */
    @ExceptionHandler(Exception::class)
    fun unhandled(error: Exception, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val requestId = request.getAttribute(RequestLog.REQUEST_ID_ATTRIBUTE) as? String
            ?: request.getHeader(RequestLog.REQUEST_ID_HEADER)?.takeIf { RequestLog.isRequestId(it) }
            ?: requestLog.newRequestId()
        requestLog.log(requestId, "unhandled: ${error.javaClass.simpleName} ${error.message?.take(200)}", "error")
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope.fail("internal", detail = error.javaClass.simpleName))
    }
}
