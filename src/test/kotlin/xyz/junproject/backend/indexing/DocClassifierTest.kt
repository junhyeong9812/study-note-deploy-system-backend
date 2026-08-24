package xyz.junproject.backend.indexing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocClassifierTest {
    @Test
    fun `표준 챕터 depth3`() {
        val meta = DocClassifier.classify("cs/lsm-tree/2-summary.md")
        assertEquals("cs", meta.topic); assertEquals("lsm-tree", meta.subject)
        assertEquals("summary", meta.docKind); assertEquals("chapter", meta.form)
        assertEquals(2, meta.depth)
    }

    @Test
    fun `depth4 중간 폴더는 topicPath로 흡수`() {
        val meta = DocClassifier.classify("cs/development-standards/operational-standards/1-question.md")
        assertEquals(listOf("cs", "development-standards", "operational-standards"), meta.topicPath)
        assertEquals("operational-standards", meta.subject)
    }

    @Test
    fun `topic 직속 파일은 subject=topic`() {
        val meta = DocClassifier.classify("cs/index.md")
        assertEquals("cs", meta.subject); assertEquals("index", meta.docKind)
    }

    @Test
    fun `과거 코테 파일명 호환`() {
        assertEquals("question", DocClassifier.classify("programmers/힙/01-더 맵게/problem.md").docKind)
        assertEquals("answer", DocClassifier.classify("programmers/힙/01-더 맵게/analyze.md").docKind)
    }

    @Test
    fun `규약 외 자유형은 post`() {
        val meta = DocClassifier.classify("reference/writing/evidence-1-cognition.md")
        assertEquals("post", meta.docKind); assertEquals("post", meta.form)
        assertEquals("writing", meta.subject)
    }
}
