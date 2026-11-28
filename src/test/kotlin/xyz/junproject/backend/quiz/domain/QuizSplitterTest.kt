package xyz.junproject.backend.quiz.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 명세: docs/cross-cutting/plans/2026-11-25/study-quiz-progress/requirement-spec.md §2.3 · §10 · §11.2 "질문의 글과 해시".
 * 자료: src/test/resources/quiz/ — 실제 cs 주제의 꼴(1부터 차례 · 0번부터 + 묶음 제목)을 작게 만든 합성 자료.
 */
class QuizSplitterTest {

    // ---------- 1부터 차례인 주제 ----------

    @Test
    fun `1부터 차례인 주제는 질문 절의 번호 줄마다 질문 하나로 쪼개진다`() {
        val split = splitResource("sequential")

        assertEquals(listOf(1, 2, 3), split.questions.map { it.number })
        assertEquals("1. (왜) 학습 손실이 작다고 모델이 좋다고 말할 수 없는 까닭은?", split.questions[0].text)
        assertEquals("3. (예측) 1등의 검증 정확도는 참값보다 높을까?", split.questions[2].text)
    }

    @Test
    fun `질문의 글은 번호 줄부터 다음 번호 줄 앞까지이고 들여 쓴 번호와 숫자로 시작하는 줄은 번호 줄이 아니다`() {
        val split = splitResource("sequential")

        assertEquals(
            "2. (경계) 세 갈래는 각각 무엇에 쓰나?\n" +
                "   - 힌트: 검증과 시험을 가른다.\n" +
                "   1. 들여 쓴 번호는 질문이 아니다.\n" +
                "6.37 / 2.687 = 2.4 처럼 숫자로 시작해도 번호 줄이 아니다.",
            split.questions[1].text,
        )
    }

    @Test
    fun `질문 절 밖의 번호 줄과 복습 기록 절은 어느 질문의 글에도 들어가지 않는다`() {
        val split = splitResource("sequential")

        assertEquals(listOf(1, 2, 3), split.questions.map { it.number })
        split.questions.forEach { question ->
            assertFalse(question.text.contains("질문 절 밖"), "질문 ${question.number} 에 질문 절 앞의 글이 섞였다")
            assertFalse(question.text.contains("복습 기록"), "질문 ${question.number} 에 복습 기록 절이 섞였다")
            assertFalse(question.text.contains("| 날짜"), "질문 ${question.number} 에 복습 기록 표가 섞였다")
        }
    }

    @Test
    fun `묶음 제목이 없는 주제의 질문은 묶음 이름이 없다`() {
        val split = splitResource("sequential")

        assertEquals(listOf(null, null, null), split.questions.map { it.group })
    }

    @Test
    fun `정답은 같은 번호의 ### N 제목 아래의 글이고 하위 제목은 정답을 자르지 않는다`() {
        val split = splitResource("sequential")

        assertEquals(listOf(1, 2, 3), split.answers.map { it.number })
        val first = split.answerOf(1)!!
        assertEquals("학습 손실과 일반화 오류", first.title)
        assertEquals(
            "- 줄이고 싶은 양은 일반화 오류다.\n\n" +
                "#### 하위 제목은 정답을 자르지 않는다\n\n" +
                "- 하위 제목 아래의 글도 1번 정답이다.",
            first.text,
        )
    }

    @Test
    fun `정답 안 코드 울타리의 제목처럼 생긴 줄은 정답을 자르지도 새 정답이 되지도 않는다`() {
        val split = splitResource("sequential")

        assertEquals(
            "```bash\n" +
                "# 이 줄은 제목이 아니다\n" +
                "### 9. 울타리 안의 가짜 정답\n" +
                "```\n\n" +
                "- 시험셋은 한 번만 잰다.",
            split.answerOf(2)!!.text,
        )
        assertNull(split.answerOf(9))
    }

    @Test
    fun `정답 쪽의 근거 절은 마지막 정답의 글에 들어가지 않는다`() {
        val split = splitResource("sequential")

        assertEquals("- 높다. 잡음이 위로 튄 후보를 고른다.", split.answerOf(3)!!.text)
    }

    @Test
    fun `번호로 한 질문의 정답만 꺼내고 다른 질문의 정답 글은 섞이지 않는다`() {
        val split = splitResource("sequential")

        val second = split.answerOf(2)!!
        assertEquals(2, second.number)
        assertEquals("세 갈래의 쓰임", second.title)
        assertFalse(second.text.contains("일반화 오류"), "1번 정답이 섞였다")
        assertFalse(second.text.contains("잡음"), "3번 정답이 섞였다")
    }

    @Test
    fun `없는 번호의 정답을 꺼내면 null 이다`() {
        val split = splitResource("sequential")

        assertNull(split.answerOf(0))
        assertNull(split.answerOf(4))
    }

    // ---------- 0번부터 · 묶음 제목이 있는 주제 ----------

    @Test
    fun `0번부터인 주제도 같은 번호끼리 맞게 쪼개진다`() {
        val split = splitResource("zero-based")

        assertEquals(listOf(0, 1, 2, 3), split.questions.map { it.number })
        assertEquals(listOf(0, 1, 2, 3), split.answers.map { it.number })
        assertEquals("\"3월 10일부터 적용\"이 안 정해주는 것", split.answerOf(0)!!.title)
        assertTrue(split.answerOf(0)!!.text.startsWith("- 비교 상대인 사건 쪽 날짜가 안 정해졌다."))
    }

    @Test
    fun `여러 줄 질문은 코드 울타리를 포함한 덩어리 전체가 하나이고 울타리 안의 번호 줄과 샵 줄은 질문을 자르지 않는다`() {
        val split = splitResource("zero-based")

        assertEquals(
            "0. 날짜 셋과 판 둘 (원본 README)\n\n" +
                "```text\n" +
                "  주문 3/7   신청 3/12\n" +
                "1. 울타리 안의 번호 줄은 질문이 아니다\n" +
                "```\n\n" +
                "- \"3월 10일부터 적용\"이 안 정해주는 것은?\n" +
                "- 날짜가 셋인 까닭은?",
            split.questions[0].text,
        )
        assertEquals(
            "1. TODO 1 — 생성자\n\n" +
                "```python\n" +
                "# 울타리 안의 샵 줄은 제목이 아니다\n" +
                "2. 울타리 안의 번호 줄\n" +
                "```\n\n" +
                "- 왜 시행일 순으로 정렬하는가?",
            split.questions[1].text,
        )
    }

    @Test
    fun `질문 절 안의 묶음 제목은 질문의 글에 섞이지 않고 그 아래 질문들의 묶음 이름으로 나온다`() {
        val split = splitResource("zero-based")

        assertEquals(listOf("A. 과제", "A. 과제", "B. 개념", "C. 통일 골격"), split.questions.map { it.group })
        assertEquals("2. 함정 (원본 README \"함정\")\n\n- 함정 다섯은 각각 무엇인가?", split.questions[2].text)
        split.questions.forEach { question ->
            assertFalse(question.text.contains("###"), "질문 ${question.number} 에 묶음 제목이 섞였다")
            assertFalse(question.text.contains("형식 안내"), "질문 ${question.number} 에 질문 앞의 주석이 섞였다")
        }
    }

    @Test
    fun `0번부터인 주제에서도 복습 기록 절은 마지막 질문에 들어가지 않는다`() {
        val split = splitResource("zero-based")

        assertEquals("3. (C1) 정책 버전 모델이 없으면 생기는 문제 셋은?", split.questions[3].text)
    }

    @Test
    fun `정답 안 코드 울타리의 절 제목과 정답 제목은 정답을 자르지 않는다`() {
        val split = splitResource("zero-based")

        assertEquals(
            "- 비교 상대인 사건 쪽 날짜가 안 정해졌다.\n\n" +
                "```text\n" +
                "## 울타리 안의 절 제목은 정답을 자르지 않는다\n" +
                "### 1. 울타리 안의 가짜 정답\n" +
                "```\n\n" +
                "- 0번 정답의 끝.",
            split.answerOf(0)!!.text,
        )
        assertEquals("TODO 1 — 생성자", split.answerOf(1)!!.title)
        assertTrue(split.answerOf(1)!!.text.startsWith("- 뒤에서부터 찾는 방법이 정렬을 전제한다."))
    }

    @Test
    fun `0번부터인 주제의 마지막 정답에는 근거 절과 실행 환경 절이 들어가지 않는다`() {
        val split = splitResource("zero-based")

        val last = split.answerOf(3)!!
        assertEquals("(C1) 정책 버전 모델이 없으면 생기는 문제 셋은?", last.title)
        assertEquals("A: 덮어쓰면 과거 재계산이 달라진다.", last.text)
    }

    @Test
    fun `백틱 울타리 안의 물결 줄은 울타리를 닫지 않는다`() {
        // 실제 꼴: network/29-tls-handshake 정답 1번 — ```text 그림 안에 "~~~~ 여기부터 암호화" 줄이 있다
        val question = "## 질문\n\n1. 가?\n\n```text\n   ~~~~ 물결 줄\n2. 울타리 안의 번호 줄\n```\n\n2. 나?\n"
        val answer = "### 1. 일\n\n```text\n  Client   Server\n          ~~~~ 여기부터 암호화\n### 2. 울타리 안의 가짜 정답\n```\n\n- 가의 정답\n\n### 2. 이\n\n- 나의 정답\n"

        val split = splitOf(question, answer)

        assertEquals(listOf(1, 2), split.questions.map { it.number })
        assertEquals("2. 나?", split.questions[1].text)
        assertEquals(listOf(1, 2), split.answers.map { it.number })
        assertTrue(split.answerOf(1)!!.text.endsWith("- 가의 정답"))
        assertEquals("- 나의 정답", split.answerOf(2)!!.text)
    }

    @Test
    fun `번호가 차례가 아니고 정답 파일의 차례가 달라도 같은 번호끼리 짝짓는다`() {
        val question = "# 제목\n\n## 질문\n\n2. 둘째 질문\n5. 다섯째 질문\n9. 아홉째 질문\n\n## 복습 기록\n"
        val answer = "# 제목\n\n## 정답\n\n### 9. 아홉\n\n- 아홉의 정답\n\n### 2. 둘\n\n- 둘의 정답\n\n### 5. 다섯\n\n- 다섯의 정답\n"

        val split = splitOf(question, answer)

        assertEquals(listOf(2, 5, 9), split.questions.map { it.number })
        assertEquals("- 둘의 정답", split.answerOf(2)!!.text)
        assertEquals("- 다섯의 정답", split.answerOf(5)!!.text)
        assertEquals("- 아홉의 정답", split.answerOf(9)!!.text)
    }

    @Test
    fun `정답 절 제목이 없거나 다른 이름이어도 ### N 제목으로 짝짓는다`() {
        val question = "# 제목\n\n## 질문\n\n1. 가?\n2. 나?\n"
        val withoutSection = "# 제목 — 정답\n\n### 1. (연결)\n\n- 가의 정답\n\n### 2. (경계)\n\n- 나의 정답\n"
        val otherSectionName = "# 제목 — 정답\n\n## 답변\n\n### 1. (연결)\n\n- 가의 정답\n\n### 2. (경계)\n\n- 나의 정답\n"

        listOf(withoutSection, otherSectionName).forEach { answer ->
            val split = splitOf(question, answer)
            assertEquals("- 가의 정답", split.answerOf(1)!!.text)
            assertEquals("- 나의 정답", split.answerOf(2)!!.text)
        }
    }

    // ---------- 줄 끝 · 파일 끝 · 두 자리 번호 ----------

    @Test
    fun `줄 끝이 CRLF 인 파일은 LF 파일과 같게 쪼개진다`() {
        val question = resource("sequential", "1-question.md")
        val answer = resource("sequential", "3-answer.md")

        val fromLf = splitOf(question, answer)
        val fromCrlf = splitOf(question.replace("\n", "\r\n"), answer.replace("\n", "\r\n"))

        assertEquals(fromLf.questions, fromCrlf.questions)
        assertEquals(fromLf.answers, fromCrlf.answers)
        (fromCrlf.questions.map { it.text } + fromCrlf.answers.map { it.text }).forEach { text ->
            assertFalse(text.contains('\r'), "글에 CR 이 남았다: $text")
        }
    }

    @Test
    fun `끝에 줄바꿈이 없는 파일도 마지막 질문과 마지막 정답을 잃지 않는다`() {
        val split = splitOf("## 질문\n\n1. 가?\n2. 나?", "### 1. 일\n- 가의 정답\n### 2. 이\n- 나의 정답")

        assertEquals(listOf(1, 2), split.questions.map { it.number })
        assertEquals("2. 나?", split.questions[1].text)
        assertEquals("- 나의 정답", split.answerOf(2)!!.text)
    }

    @Test
    fun `두 자리 번호도 질문이 되고 1번과 10번대의 정답이 섞이지 않는다`() {
        val numbers = 1..12
        val question = "## 질문\n\n" + numbers.joinToString("\n") { "$it. 질문$it 본문" } + "\n\n## 복습 기록\n"
        val answer = "## 정답\n\n" + numbers.joinToString("\n\n") { "### $it. 제목$it\n\n- 정답$it 본문" } + "\n"

        val split = splitOf(question, answer)

        assertEquals(numbers.toList(), split.questions.map { it.number })
        assertEquals("12. 질문12 본문", split.questions.last().text)
        assertEquals("- 정답1 본문", split.answerOf(1)!!.text)
        assertEquals("- 정답10 본문", split.answerOf(10)!!.text)
        assertEquals("- 정답12 본문", split.answerOf(12)!!.text)
    }

    // ---------- 질문 해시 ----------

    @Test
    fun `질문 해시는 같은 글이면 언제나 같다`() {
        val first = splitResource("zero-based")
        val second = splitResource("zero-based")

        assertEquals(first.questions.map { it.hash }, second.questions.map { it.hash })
        assertEquals(QuizSplitter.questionHash("1. 가?"), QuizSplitter.questionHash("1. 가?"))
        assertTrue(QuizSplitter.questionHash("1. 가?").isNotBlank())
    }

    @Test
    fun `쪼갠 질문의 해시는 그 질문의 글로 낸 해시와 같다`() {
        val split = splitResource("zero-based")

        split.questions.forEach { question ->
            assertEquals(QuizSplitter.questionHash(question.text), question.hash, "질문 ${question.number}")
        }
    }

    @Test
    fun `끝의 공백과 빈 줄만 다른 두 글의 해시는 같다`() {
        assertEquals(
            QuizSplitter.questionHash("1. 가?\n- 힌트"),
            QuizSplitter.questionHash("1. 가?\n- 힌트  \n\n \n"),
        )
    }

    @Test
    fun `다음 질문 앞의 빈 줄 수가 달라도 질문의 해시는 같다`() {
        val tight = splitOf("## 질문\n\n1. 가?\n2. 나?\n", "### 1. 일\n- 가\n### 2. 이\n- 나\n")
        val loose = splitOf("## 질문\n\n1. 가?   \n\n\n2. 나?\n", "### 1. 일\n- 가\n### 2. 이\n- 나\n")

        assertEquals(tight.questions[0].hash, loose.questions[0].hash)
    }

    @Test
    fun `글자 하나가 다르면 해시가 다르다`() {
        assertNotEquals(QuizSplitter.questionHash("1. 가나다?"), QuizSplitter.questionHash("1. 가나라?"))
        assertNotEquals(QuizSplitter.questionHash("1. 가?\n- 힌트"), QuizSplitter.questionHash("1. 가?\n- 힌트!"))
    }

    @Test
    fun `같은 글이 번호만 바뀌면 해시가 다르다`() {
        val before = splitOf("## 질문\n\n1. 같은 질문?\n", "### 1. 일\n- 정답\n")
        val after = splitOf("## 질문\n\n2. 같은 질문?\n", "### 2. 이\n- 정답\n")

        assertNotEquals(before.questions[0].hash, after.questions[0].hash)
    }

    @Test
    fun `묶음 제목은 해시에 들어가지 않는다`() {
        val underA = splitOf("## 질문\n\n### A. 과제\n\n1. 같은 질문?\n", "### 1. 일\n- 정답\n")
        val underB = splitOf("## 질문\n\n### B. 개념\n\n1. 같은 질문?\n", "### 1. 일\n- 정답\n")

        assertEquals("A. 과제", underA.questions[0].group)
        assertEquals("B. 개념", underB.questions[0].group)
        assertEquals(underA.questions[0].hash, underB.questions[0].hash)
    }

    // ---------- 쪼갤 수 없는 주제 ----------

    @Test
    fun `질문 절이 없으면 질문 절 없음으로 답한다`() {
        val result = unsplittableOf("# 제목\n\n## 요약\n\n1. 가?\n", "### 1. 일\n- 정답\n")

        assertEquals(UnsplittableReason.NO_QUESTION_SECTION, result.reason)
    }

    @Test
    fun `질문 절에 번호 질문이 없으면 번호 질문 없음으로 답한다`() {
        val result = unsplittableOf(
            "# 제목\n\n## 질문\n\n- 번호 없는 질문?\n\n## 복습 기록\n\n1. 절 밖의 번호 줄\n",
            "### 1. 일\n- 정답\n",
        )

        assertEquals(UnsplittableReason.NO_NUMBERED_QUESTION, result.reason)
    }

    @Test
    fun `정답이 모자라면 정답 없음과 그 번호로 답한다`() {
        val result = unsplittableOf("## 질문\n\n1. 가?\n2. 나?\n3. 다?\n", "### 1. 일\n- 가\n### 2. 이\n- 나\n")

        assertEquals(UnsplittableReason.MISSING_ANSWER, result.reason)
        assertEquals(listOf(3), result.numbers)
    }

    @Test
    fun `정답이 남으면 정답 남음과 그 번호로 답한다`() {
        val result = unsplittableOf(
            "## 질문\n\n1. 가?\n2. 나?\n",
            "### 1. 일\n- 가\n### 2. 이\n- 나\n### 4. 사\n- 라\n",
        )

        assertEquals(UnsplittableReason.EXTRA_ANSWER, result.reason)
        assertEquals(listOf(4), result.numbers)
    }

    @Test
    fun `질문 번호와 정답 번호가 한 칸씩 어긋나면 차례로 짝짓지 않고 쪼갤 수 없다고 답한다`() {
        val result = unsplittableOf(
            "## 질문\n\n0. 영?\n1. 일?\n2. 이?\n",
            "### 1. 일\n- 가\n### 2. 이\n- 나\n### 3. 삼\n- 다\n",
        )

        assertTrue(
            result.reason == UnsplittableReason.MISSING_ANSWER || result.reason == UnsplittableReason.EXTRA_ANSWER,
            "어긋난 번호인데 이유가 ${result.reason}",
        )
    }

    @Test
    fun `질문 번호가 겹치면 질문 번호 겹침과 그 번호로 답한다`() {
        val result = unsplittableOf("## 질문\n\n1. 가?\n1. 나?\n2. 다?\n", "### 1. 일\n- 가\n### 2. 이\n- 나\n")

        assertEquals(UnsplittableReason.DUPLICATE_QUESTION_NUMBER, result.reason)
        assertEquals(listOf(1), result.numbers)
    }

    @Test
    fun `정답 번호가 겹치면 정답 번호 겹침과 그 번호로 답한다`() {
        val result = unsplittableOf(
            "## 질문\n\n1. 가?\n2. 나?\n",
            "### 1. 일\n- 가\n### 2. 이\n- 나\n### 2. 이 또\n- 나 또\n",
        )

        assertEquals(UnsplittableReason.DUPLICATE_ANSWER_NUMBER, result.reason)
        assertEquals(listOf(2), result.numbers)
    }

    @Test
    fun `빈 파일을 받아도 예외로 죽지 않고 쪼갤 수 없다고 답한다`() {
        val bothEmpty = assertDoesNotThrow<QuizSplitResult> { QuizSplitter.split("", "") }
        assertEquals(UnsplittableReason.NO_QUESTION_SECTION, assertInstanceOf(QuizSplitResult.Unsplittable::class.java, bothEmpty).reason)

        val answerEmpty = assertDoesNotThrow<QuizSplitResult> { QuizSplitter.split("## 질문\n\n1. 가?\n2. 나?\n", "") }
        val missing = assertInstanceOf(QuizSplitResult.Unsplittable::class.java, answerEmpty)
        assertEquals(UnsplittableReason.MISSING_ANSWER, missing.reason)
        assertEquals(listOf(1, 2), missing.numbers)
    }

    // ---------- 전수 확인 (실제 cs 폴더 — 환경 변수가 있을 때만) ----------

    @Test
    fun `실제 cs 의 모든 주제가 쪼개지고 질문 번호 집합과 정답 번호 집합이 같다`() {
        val csDirectory = System.getenv("STUDY_NOTE_CS_DIR")
        assumeTrue(csDirectory != null && Path.of(csDirectory).isDirectory(), "STUDY_NOTE_CS_DIR 가 없어 전수 확인을 건너뜀")

        val topicDirectories = Files.walk(Path.of(csDirectory!!)).use { paths ->
            paths.filter { it.fileName.toString() == "1-question.md" && it.isRegularFile() }
                .map { it.parent }
                .sorted()
                .toList()
        }
        val unsplittable = mutableListOf<String>()
        val numberMismatch = mutableListOf<String>()
        var questionCount = 0
        for (topicDirectory in topicDirectories) {
            val answerFile = topicDirectory.resolve("3-answer.md")
            val answerMarkdown = if (answerFile.isRegularFile()) answerFile.readText() else ""
            when (val result = QuizSplitter.split(topicDirectory.resolve("1-question.md").readText(), answerMarkdown)) {
                is QuizSplitResult.Unsplittable -> unsplittable += "$topicDirectory: ${result.reason} ${result.numbers}"
                is QuizSplitResult.Split -> {
                    questionCount += result.questions.size
                    val questionNumbers = result.questions.map { it.number }.toSet()
                    val answerNumbers = result.answers.map { it.number }.toSet()
                    if (questionNumbers != answerNumbers || result.questions.any { result.answerOf(it.number) == null }) {
                        numberMismatch += "$topicDirectory: 질문 $questionNumbers / 정답 $answerNumbers"
                    }
                }
            }
        }
        println("전수 확인: 주제 ${topicDirectories.size} · 질문 $questionCount · 쪼갤 수 없음 ${unsplittable.size}")

        assertTrue(topicDirectories.isNotEmpty(), "$csDirectory 아래에 주제가 하나도 없다")
        assertTrue(questionCount > 0, "쪼갠 질문이 하나도 없다")
        assertEquals(emptyList<String>(), unsplittable, "쪼갤 수 없는 주제 ${unsplittable.size}개")
        assertEquals(emptyList<String>(), numberMismatch, "질문 번호와 정답 번호가 다른 주제 ${numberMismatch.size}개")
    }

    // ---------- 도움 함수 ----------

    private fun resource(topic: String, fileName: String): String =
        javaClass.getResource("/quiz/$topic/$fileName")!!.readText()

    private fun splitResource(topic: String): QuizSplitResult.Split =
        splitOf(resource(topic, "1-question.md"), resource(topic, "3-answer.md"))

    private fun splitOf(questionMarkdown: String, answerMarkdown: String): QuizSplitResult.Split =
        assertInstanceOf(QuizSplitResult.Split::class.java, QuizSplitter.split(questionMarkdown, answerMarkdown))

    private fun unsplittableOf(questionMarkdown: String, answerMarkdown: String): QuizSplitResult.Unsplittable =
        assertInstanceOf(QuizSplitResult.Unsplittable::class.java, QuizSplitter.split(questionMarkdown, answerMarkdown))
}
