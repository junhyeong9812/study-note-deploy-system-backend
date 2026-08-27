package xyz.junproject.backend.api

/** 응답 정규화 봉투 — llm wrapper와 동일 규약. success 플래그 하나로 분기한다. */
object Envelope {
    fun ok(data: Any?): Map<String, Any?> = mapOf("success" to true, "data" to data)

    fun fail(code: String, detail: String? = null, retryAfter: Int? = null): Map<String, Any?> {
        val error = buildMap<String, Any?> {
            put("code", code)
            detail?.let { put("detail", it) }
            retryAfter?.let { put("retry_after", it) }
        }
        return mapOf("success" to false, "error" to error)
    }
}
