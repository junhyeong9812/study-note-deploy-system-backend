package xyz.junproject.backend.shared.infra

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import xyz.junproject.backend.chat.usecase.EscalatePort
import xyz.junproject.backend.chat.usecase.EscalateRefusal
import xyz.junproject.backend.chat.usecase.EscalateResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Claude 실행 자리를 부른다(명세 §20.1 · §21) — `POST <runnerUrl>/runs` · 머리글 `X-Claude-Runner-Secret` · 몸은 `Content-Length` 가 있는 바이트
 * `{"purpose":"chat","prompt":…,"request_id":…}` · 리다이렉트를 따라가지 않는다 · 연결 [connectTimeout] · 받는 것 전체가 부르는 쪽의 기한까지 · 재시도 없음.
 * 결과는 값([EscalateResult]) — 상태 코드만으로 가르지 않고 `error.code` 로.
 * 설정(`CLAUDE_RUNNER_URL` · `CLAUDE_RUNNER_SECRET`)이 비면 [available] 이 거짓이고 부르지 않는다.
 * 프롬프트 · 답 · 비밀값 · 실행 자리의 몸은 결과의 글 · 예외의 글 · 로그에 싣지 않는다(이 클래스는 로그를 남기지 않는다 — 부르는 쪽이 결과의 낱말만 남긴다).
 */
class ClaudeRunnerClient(
    runnerUrl: String,
    private val secret: String,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val connectTimeout: Duration = Duration.ofSeconds(3),
) : EscalatePort {
    private val baseUrl = runnerUrl.trim().trimEnd('/')

    override val available: Boolean get() = baseUrl.isNotBlank() && secret.isNotBlank()
    /** 실행 자리가 설정돼 있으면 자동 넘김을 할 수 있다 */
    override val autoEscalation: Boolean get() = available

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)   // 비밀값이 다른 곳으로 가지 않게
            .version(HttpClient.Version.HTTP_1_1)          // 실행 자리는 HTTP/1.1 만 받는다 — 업그레이드를 시도하지 않는다
            .build()
    }

    override fun ask(requestId: String, prompt: String, deadline: Instant): EscalateResult {
        if (!available) return EscalateResult.Refused(EscalateRefusal.UNAVAILABLE)
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.isNegative || remaining.isZero) return EscalateResult.Refused(EscalateRefusal.TIMEOUT)
        // 바이트 배열 = Content-Length — 실행 자리는 chunked 요청을 411 로 거절한다
        val body = objectMapper.writeValueAsBytes(mapOf("purpose" to PURPOSE, "prompt" to prompt, "request_id" to requestId))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/runs"))
            .timeout(remaining)
            .header("Content-Type", "application/json; charset=utf-8")
            .header(SECRET_HEADER, secret)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val call = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        val response = try {
            // 기한은 호출 전체의 절대 마감 — 연결 · 머리글 · 몸을 다 받는 데까지
            call.get(remaining.toNanos(), TimeUnit.NANOSECONDS)
        } catch (timeout: TimeoutException) {
            call.cancel(true)
            return EscalateResult.Refused(EscalateRefusal.TIMEOUT)
        } catch (interrupted: InterruptedException) {
            // 기다리는 쪽이 떠났다 — 결과로 삼키지 않는다(이력을 쓰지 않게). 글에는 아무 내용도 싣지 않는다
            call.cancel(true)
            throw InterruptedException("claude runner call interrupted")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            return EscalateResult.Refused(
                if (cause is HttpTimeoutException && cause !is HttpConnectTimeoutException) EscalateRefusal.TIMEOUT else EscalateRefusal.UNAVAILABLE)
        }
        return resultOf(response.statusCode(), response.body())
    }

    /** 실행 자리의 답 → 결과(명세 §20.1 의 표) */
    private fun resultOf(status: Int, body: ByteArray): EscalateResult {
        val envelope: JsonNode? = try { objectMapper.readTree(body) } catch (_: Exception) { null }
        if (status == 200) {
            val data = envelope?.takeIf { it.isObject && it.path("success").isBoolean && it.path("success").booleanValue() }?.path("data")
            val text = data?.path("text")
            if (text != null && text.isString && text.stringValue().isNotBlank()) {
                val truncated = data.path("truncated").let { it.isBoolean && it.booleanValue() }
                return EscalateResult.Answered(text.stringValue(), truncated)
            }
            return EscalateResult.Refused(EscalateRefusal.UNAVAILABLE)
        }
        val code = envelope?.takeIf { it.isObject }?.path("error")?.path("code")
            ?.takeIf { it.isString }?.stringValue()?.takeIf { RUNNER_CODE.matches(it) }
        return when (status) {
            429 -> EscalateResult.Refused(when (code) {
                "busy" -> EscalateRefusal.BUSY
                "limit_reached" -> EscalateRefusal.LIMIT_REACHED
                "disabled" -> EscalateRefusal.DISABLED
                else -> EscalateRefusal.UNAVAILABLE
            })
            504 -> EscalateResult.Refused(EscalateRefusal.TIMEOUT)
            in BACKEND_FAULT_STATUSES -> EscalateResult.Refused(EscalateRefusal.REJECTED, code)
            else -> EscalateResult.Refused(EscalateRefusal.UNAVAILABLE)
        }
    }

    companion object {
        const val SECRET_HEADER = "X-Claude-Runner-Secret"
        const val PURPOSE = "chat"
        /** backend 의 설정 · 코드가 틀렸다는 답 — 비밀값 불일치 · 몸의 길이 · 프롬프트 초과 · 경로 */
        private val BACKEND_FAULT_STATUSES = setOf(400, 401, 404, 411, 413, 422)
        /** 로그에 싣는 실행 자리의 코드 — 고정 낱말의 꼴만(다른 글자가 로그로 새지 않게) */
        private val RUNNER_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}
