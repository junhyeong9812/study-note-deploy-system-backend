package xyz.junproject.backend.indexing

/**
 * md → 청크 분할 (es-index.md D1).
 * h2(##) 단위 → 8KB 초과 시 h3 → 그래도 크면 문단 경계. 1KB 미만 파일은 통째 1청크.
 * h2 이전 전문(前文)은 chunk 0.
 */
data class Chunk(val heading: String, val content: String)

object Chunker {
    private const val SPLIT_THRESHOLD_BYTES = 8 * 1024
    private const val SINGLE_CHUNK_BYTES = 1024

    fun chunk(markdown: String): List<Chunk> {
        val title = Regex("^# (.+)$", RegexOption.MULTILINE)
            .find(markdown)?.groupValues?.get(1)?.trim() ?: ""
        if (markdown.toByteArray().size < SINGLE_CHUNK_BYTES) {
            return listOf(Chunk(title, markdown.trim())).filter { it.content.isNotBlank() }
        }
        val sections = splitByHeading(markdown, "## ")
        return sections.flatMap { (heading, body) ->
            val headingTrail = listOf(title, heading).filter { it.isNotBlank() }.joinToString(" > ")
            if (body.toByteArray().size <= SPLIT_THRESHOLD_BYTES) {
                listOf(Chunk(headingTrail, body.trim()))
            } else {
                splitByHeading(body, "### ").flatMap { (subHeading, subBody) ->
                    val trail = listOf(headingTrail, subHeading).filter { it.isNotBlank() }.joinToString(" > ")
                    if (subBody.toByteArray().size <= SPLIT_THRESHOLD_BYTES) {
                        listOf(Chunk(trail, subBody.trim()))
                    } else {
                        splitByParagraph(subBody).map { Chunk(trail, it) }
                    }
                }
            }
        }.filter { it.content.isNotBlank() }
    }

    /** heading 마커 기준 분할 — 마커 이전 앞부분은 heading "" 섹션으로 보존 */
    private fun splitByHeading(text: String, marker: String): List<Pair<String, String>> {
        val lines = text.lines()
        val sections = mutableListOf<Pair<String, String>>()
        var heading = ""
        val buffer = StringBuilder()
        for (line in lines) {
            if (line.startsWith(marker)) {
                if (buffer.isNotBlank()) sections.add(heading to buffer.toString())
                heading = line.removePrefix(marker).trim()
                buffer.clear()
            } else buffer.appendLine(line)
        }
        if (buffer.isNotBlank()) sections.add(heading to buffer.toString())
        return sections
    }

    /** 문단(빈 줄) 경계로 8KB 이하 덩어리 누적 분할 — 상한은 바이트 기준(한국어 = 글자당 3바이트) */
    private fun splitByParagraph(text: String): List<String> {
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        var bufferBytes = 0
        for (paragraph in text.split(Regex("\n\n+"))) {
            val paragraphBytes = paragraph.toByteArray().size
            if (bufferBytes > 0 && bufferBytes + paragraphBytes > SPLIT_THRESHOLD_BYTES) {
                result.add(buffer.toString().trim()); buffer.clear(); bufferBytes = 0
            }
            buffer.appendLine(paragraph).appendLine()
            bufferBytes += paragraphBytes + 2
        }
        if (buffer.isNotBlank()) result.add(buffer.toString().trim())
        return result
    }
}
