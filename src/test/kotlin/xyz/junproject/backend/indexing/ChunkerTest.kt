package xyz.junproject.backend.indexing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChunkerTest {
    @Test
    fun `1KB 미만 파일은 통째 1청크`() {
        val chunks = Chunker.chunk("# 제목\n\n짧은 본문")
        assertEquals(1, chunks.size)
        assertEquals("제목", chunks[0].heading)
    }

    @Test
    fun `h2 단위 분할 + 전문은 chunk0 + 헤딩 트레일`() {
        val markdown = buildString {
            appendLine("# 문서제목"); appendLine("전문 문단 ".repeat(120))
            appendLine("## 첫 절"); appendLine("본문1 ".repeat(120))
            appendLine("## 둘째 절"); appendLine("본문2 ".repeat(120))
        }
        val chunks = Chunker.chunk(markdown)
        assertEquals(3, chunks.size)
        assertEquals("문서제목", chunks[0].heading)              // 전문
        assertEquals("문서제목 > 첫 절", chunks[1].heading)
        assertTrue(chunks[2].content.startsWith("본문2"))
    }

    @Test
    fun `8KB 초과 절은 h3로 재분할`() {
        val big = "가나다라마바사 ".repeat(700)                  // > 8KB
        val markdown = "# T\n\n## 큰절\n### 소절A\n$big\n### 소절B\n$big"
        val chunks = Chunker.chunk(markdown)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.any { it.heading == "T > 큰절 > 소절A" })
    }

    @Test
    fun `h3도 넘치면 문단 경계 분할 - 청크당 8KB 상한 유지`() {
        val paragraphs = (1..30).joinToString("\n\n") { "문단$it " + "내용 ".repeat(200) }
        val chunks = Chunker.chunk("# T\n\n## 거대절\n$paragraphs")
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.content.toByteArray().size <= 9 * 1024) }
    }
}
