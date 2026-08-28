package xyz.junproject.backend.indexing.domain

/** 경로·파일명 → 색인 필드 매핑 (es-index.md D2·D3). depth 2~4 편차를 규칙 하나로 흡수한다. */
data class DocMeta(
    val path: String,          // repo 상대 경로 (정본 키)
    val topic: String,         // 첫 폴더
    val topicPath: List<String>,
    val subject: String,       // 마지막 폴더 = 주제 (topic 직속 파일이면 topic)
    val depth: Int,
    val docKind: String,       // question|summary|answer|index|readme|post
    val form: String,          // chapter|post
)

object DocClassifier {
    private val kindByFilename = mapOf(
        "1-question.md" to "question",
        "2-summary.md" to "summary",
        "3-answer.md" to "answer",
        // 과거 호환 (programmers 규약 통일 이전 커밋 처리용)
        "problem.md" to "question",
        "analyze.md" to "answer",
        "index.md" to "index",
        "README.md" to "readme",
    )
    private val chapterKinds = setOf("question", "summary", "answer")

    fun classify(path: String): DocMeta {
        val segments = path.split("/")
        val folders = segments.dropLast(1)
        val filename = segments.last()
        val docKind = kindByFilename[filename] ?: "post"
        val form = if (docKind in chapterKinds) "chapter" else if (docKind == "post") "post" else "chapter"
        return DocMeta(
            path = path,
            topic = folders.firstOrNull() ?: "",
            topicPath = folders,
            subject = folders.lastOrNull() ?: (folders.firstOrNull() ?: ""),
            depth = folders.size,
            docKind = docKind,
            form = if (docKind in setOf("index", "readme")) "chapter" else form,
        )
    }
}
