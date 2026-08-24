package xyz.junproject.backend.indexing

/**
 * md → 청크 분할 (es-index.md D1).
 * h2(##) 단위 → 8KB 초과 시 h3 → 그래도 크면 문단 경계. 1KB 미만 파일은 통째 1청크.
 * h2 이전 전문(前文)은 chunk 0.
 */
data class Chunk(val heading: String, val content: String)

data class ChunkedDoc(val title: String, val chunks: List<Chunk>)

object Chunker {
    private const val SPLIT_THRESHOLD_BYTES = 8 * 1024
    private const val SINGLE_CHUNK_BYTES = 1024

    /** title = h1, 없으면 빈 문자열(호출부가 파일명 폴백 — es-index D4). heading 트레일은 h2부터. */
    fun chunk(markdown: String): ChunkedDoc {
        val title = Regex("^# (.+)$", RegexOption.MULTILINE)
            .find(markdown)?.groupValues?.get(1)?.trim() ?: ""
        if (markdown.toByteArray().size < SINGLE_CHUNK_BYTES) {
            return ChunkedDoc(title,
                listOf(Chunk("", markdown.trim())).filter { it.content.isNotBlank() })
        }
        val sections = splitByHeading(markdown, "## ")
        val chunks = sections.flatMap { (heading, body) ->
            val headingTrail = heading                             // h2부터 (title 중복 계상 방지)
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
        return ChunkedDoc(title, chunks)
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
        for (rawParagraph in text.split(Regex("\n\n+"))) {
            // 단일 문단 자체가 상한 초과(무개행 거대 코드블록 등)면 강제 분할 (감사 지적)
            for (paragraph in hardSplit(rawParagraph)) {
                val paragraphBytes = paragraph.toByteArray().size
                if (bufferBytes > 0 && bufferBytes + paragraphBytes > SPLIT_THRESHOLD_BYTES) {
                    result.add(buffer.toString().trim()); buffer.clear(); bufferBytes = 0
                }
                buffer.appendLine(paragraph).appendLine()
                bufferBytes += paragraphBytes + 2
            }
        }
        if (buffer.isNotBlank()) result.add(buffer.toString().trim())
        return result
    }

    /** 상한을 넘는 단일 문단을 줄 경계(없으면 문자 경계)로 상한 이하 조각으로 강제 분할 */
    private fun hardSplit(paragraph: String): List<String> {
        if (paragraph.toByteArray().size <= SPLIT_THRESHOLD_BYTES) return listOf(paragraph)
        val pieces = mutableListOf<String>()
        val buffer = StringBuilder(); var bufferBytes = 0
        for (line in paragraph.lines()) {
            val chunked = if (line.toByteArray().size > SPLIT_THRESHOLD_BYTES)
                line.chunked(SPLIT_THRESHOLD_BYTES / 4) else listOf(line)   // 문자 경계 최후 수단
            for (piece in chunked) {
                val pieceBytes = piece.toByteArray().size
                if (bufferBytes > 0 && bufferBytes + pieceBytes > SPLIT_THRESHOLD_BYTES) {
                    pieces.add(buffer.toString()); buffer.clear(); bufferBytes = 0
                }
                buffer.appendLine(piece); bufferBytes += pieceBytes + 1
            }
        }
        if (buffer.isNotBlank()) pieces.add(buffer.toString())
        return pieces
    }
}
