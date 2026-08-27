package xyz.junproject.backend.domain

data class RewriteOutcome(
    val used: Boolean,
    val keywords: List<String> = emptyList(),
    val expanded: List<String> = emptyList(),
    val topic: String? = null,
    val docKind: String? = null,
)
