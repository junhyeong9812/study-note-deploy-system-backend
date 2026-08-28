package xyz.junproject.backend.content.domain

import xyz.junproject.backend.indexing.domain.DocClassifier

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TreeBuilderTest {
    @Test
    fun `리프 폴더가 주제가 되고 문서를 담는다`() {
        val tree = TreeBuilder.build(listOf(
            "cs/systems/lsm-tree/1-question.md",
            "cs/systems/lsm-tree/2-summary.md",
            "cs/index.md",
            "practice/programmers/힙/01-더 맵게/1-question.md",
        ))
        val cs = tree.children.first { it.name == "cs" }
        assertEquals(listOf("cs/index.md"), cs.docs.map { it.path })     // 폴더 직속 문서
        val lsmTree = cs.children.first { it.name == "systems" }.children.first()
        assertTrue(lsmTree.isSubject)                                     // 리프 폴더 = 주제
        assertEquals(2, lsmTree.docs.size)
        assertEquals("cs/systems/lsm-tree", lsmTree.path)
        val heap = tree.children.first { it.name == "practice" }
            .children.first().children.first { it.name == "힙" }
        assertFalse(heap.isSubject)                                       // 하위 폴더가 있으면 주제 아님
        assertTrue(heap.children.first().isSubject)
    }

    @Test
    fun `자식 폴더는 이름순 정렬`() {
        val tree = TreeBuilder.build(listOf("b/x/1-question.md", "a/y/1-question.md"))
        assertEquals(listOf("a", "b"), tree.children.map { it.name })
    }
}
