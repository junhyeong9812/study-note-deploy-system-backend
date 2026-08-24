package xyz.junproject.backend.common

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

    @ExceptionHandler(Exception::class)
    fun unhandled(error: Exception): ResponseEntity<Map<String, Any?>> {
        val requestId = requestLog.newRequestId()
        requestLog.log(requestId, "unhandled: ${error.javaClass.simpleName} ${error.message?.take(200)}", "error")
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope.fail("internal", detail = error.javaClass.simpleName))
    }
}
