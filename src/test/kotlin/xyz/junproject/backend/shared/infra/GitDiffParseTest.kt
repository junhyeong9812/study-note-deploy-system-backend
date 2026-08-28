package xyz.junproject.backend.shared.infra

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** diff --name-status 파싱 — 리뷰 B9: 여기가 틀리면 문서가 조용히 인덱스에서 사라진다. */
class GitDiffParseTest {
    @Test
    fun `A M D 기본 파싱`() {
        val parsed = GitRepository.parseNameStatus("A\tcs/x/1-question.md\nM\tcs/y/2-summary.md\nD\tcs/z/3-answer.md\n")
        assertEquals(listOf('A' to "cs/x/1-question.md", 'M' to "cs/y/2-summary.md", 'D' to "cs/z/3-answer.md"), parsed)
    }

    @Test
    fun `rename은 구경로 D + 신경로 A로 분해`() {
        val parsed = GitRepository.parseNameStatus("R100\tprogrammers/힙/01/problem.md\tprogrammers/힙/01/1-question.md")
        assertEquals(listOf('D' to "programmers/힙/01/problem.md", 'A' to "programmers/힙/01/1-question.md"), parsed)
    }

    @Test
    fun `한글·공백 경로 보존`() {
        val parsed = GitRepository.parseNameStatus("M\tprogrammers/동적계획법/01-N으로 표현/3-answer.md")
        assertEquals('M' to "programmers/동적계획법/01-N으로 표현/3-answer.md", parsed.single())
    }

    @Test
    fun `빈 출력은 빈 목록`() {
        assertTrue(GitRepository.parseNameStatus("\n").isEmpty())
    }
}
