package xyz.junproject.backend.quiz.domain

import java.security.MessageDigest

/**
 * cs 주제의 질문 파일(`1-question.md`)과 정답 파일(`3-answer.md`)을 질문 단위로 쪼개 같은 번호끼리 짝짓는다.
 * 뼈대 — 구현은 task 03a 의 구현 워커가 채운다(계약은 QuizSplitterTest).
 */
object QuizSplitter {
    /** 두 파일의 글을 받아 질문 단위로 쪼갠다. 쪼갤 수 없으면 [QuizSplitResult.Unsplittable] — 예외를 던지지 않는다. */
    fun split(questionMarkdown: String, answerMarkdown: String): QuizSplitResult {
        val questionLines = linesOutsideFence(questionMarkdown)
        if (questionLines.none { line -> line.isQuestionSectionHeading() }) {
            return QuizSplitResult.Unsplittable(UnsplittableReason.NO_QUESTION_SECTION)
        }
        val questions = splitQuestions(questionLines)
        if (questions.isEmpty()) return QuizSplitResult.Unsplittable(UnsplittableReason.NO_NUMBERED_QUESTION)
        val answers = splitAnswers(linesOutsideFence(answerMarkdown))

        val questionNumbers = questions.map { question -> question.number }
        val answerNumbers = answers.map { answer -> answer.number }
        val duplicateQuestionNumbers = duplicatesOf(questionNumbers)
        if (duplicateQuestionNumbers.isNotEmpty()) {
            return QuizSplitResult.Unsplittable(UnsplittableReason.DUPLICATE_QUESTION_NUMBER, duplicateQuestionNumbers)
        }
        val duplicateAnswerNumbers = duplicatesOf(answerNumbers)
        if (duplicateAnswerNumbers.isNotEmpty()) {
            return QuizSplitResult.Unsplittable(UnsplittableReason.DUPLICATE_ANSWER_NUMBER, duplicateAnswerNumbers)
        }
        val missingNumbers = (questionNumbers - answerNumbers.toSet()).sorted()
        if (missingNumbers.isNotEmpty()) return QuizSplitResult.Unsplittable(UnsplittableReason.MISSING_ANSWER, missingNumbers)
        val extraNumbers = (answerNumbers - questionNumbers.toSet()).sorted()
        if (extraNumbers.isNotEmpty()) return QuizSplitResult.Unsplittable(UnsplittableReason.EXTRA_ANSWER, extraNumbers)

        return QuizSplitResult.Split(questions, answers)
    }

    /** 질문의 글에서 끝의 공백 · 빈 줄을 뺀 것으로 낸 해시. 같은 글이면 언제나 같은 값. */
    fun questionHash(questionText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizeLineEnds(questionText).trimEnd().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 질문 절(`## 질문`) 안의 번호 줄마다 질문 하나. `#` · `##` 제목은 절을 끝내고 `###` 이하는 묶음 이름이다. */
    private fun splitQuestions(lines: List<MarkdownLine>): List<QuizQuestion> {
        val questions = mutableListOf<QuizQuestion>()
        var insideQuestionSection = false
        var group: String? = null
        var currentNumber: Int? = null
        val currentLines = mutableListOf<String>()

        fun finishCurrentQuestion() {
            val number = currentNumber ?: return
            val text = currentLines.joinToString("\n").trimEnd()
            questions += QuizQuestion(number, group, text, questionHash(text))
            currentNumber = null
            currentLines.clear()
        }

        for (line in lines) {
            val headingLevel = line.headingLevel()
            if (headingLevel != null) {
                finishCurrentQuestion()
                if (headingLevel <= 2) {
                    insideQuestionSection = line.isQuestionSectionHeading()
                    group = null
                } else if (insideQuestionSection) {
                    group = line.text.trimStart('#').trim()
                }
                continue
            }
            if (!insideQuestionSection) continue
            val number = line.questionNumber()
            if (number != null) {
                finishCurrentQuestion()
                currentNumber = number
            }
            if (currentNumber != null) currentLines += line.text
        }
        finishCurrentQuestion()
        return questions
    }

    /**
     * 파일 어디서든 `### N.` 제목마다 정답 하나. 글은 다음 1~3단계 제목 앞까지다.
     * 단순화: 정답 파일의 굵은 묶음 줄(`**B. 개념**`)은 제목이 아니라서 앞 정답의 글 끝에 붙어 나온다 — 퀴즈 화면에 묶음 이름이 한 줄 더 보일 뿐이다. 정답 글에서 빼야 하면 이 함수에서 굵은 한 줄을 묶음 줄로 가른다.
     */
    private fun splitAnswers(lines: List<MarkdownLine>): List<QuizAnswer> {
        val answers = mutableListOf<QuizAnswer>()
        var currentHeading: MatchResult? = null
        val currentLines = mutableListOf<String>()

        fun finishCurrentAnswer() {
            val heading = currentHeading ?: return
            val text = currentLines.dropWhile { text -> text.isBlank() }.joinToString("\n").trimEnd()
            answers += QuizAnswer(heading.groupValues[1].toInt(), heading.groupValues[2].trim(), text)
            currentHeading = null
            currentLines.clear()
        }

        for (line in lines) {
            val headingLevel = line.headingLevel()
            if (headingLevel != null && headingLevel <= 3) {
                finishCurrentAnswer()
                if (headingLevel == 3) currentHeading = ANSWER_HEADING.matchEntire(line.text)
                continue
            }
            if (currentHeading != null) currentLines += line.text
        }
        finishCurrentAnswer()
        return answers
    }

    private fun duplicatesOf(numbers: List<Int>): List<Int> =
        numbers.groupingBy { number -> number }.eachCount().filterValues { count -> count > 1 }.keys.sorted()

    private fun normalizeLineEnds(markdown: String): String = markdown.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * 줄마다 코드 울타리 안(여는 줄 · 닫는 줄 포함)인지를 붙인다.
     * 울타리는 연 것과 같은 글자 · 같은 길이 이상 · 뒤에 글이 없는 줄로만 닫힌다(백틱 울타리 안의 물결 줄은 닫지 않는다).
     */
    private fun linesOutsideFence(markdown: String): List<MarkdownLine> {
        var openFence: Fence? = null
        return normalizeLineEnds(markdown).split('\n').map { text ->
            val fence = openFence
            if (fence == null) {
                openFence = openingFenceOf(text)
                MarkdownLine(text, insideFence = openFence != null)
            } else {
                if (fence.isClosedBy(text)) openFence = null
                MarkdownLine(text, insideFence = true)
            }
        }
    }

    private fun openingFenceOf(text: String): Fence? {
        val trimmed = text.trimStart()
        val character = trimmed.firstOrNull()?.takeIf { first -> first == '`' || first == '~' } ?: return null
        val length = trimmed.takeWhile { each -> each == character }.length
        if (length < 3) return null
        // 백틱 울타리의 뒤 글(언어 이름)에 백틱이 있으면 울타리가 아니라 인라인 코드다(CommonMark).
        if (character == '`' && trimmed.drop(length).contains('`')) return null
        return Fence(character, length)
    }

    private data class Fence(val character: Char, val length: Int) {
        fun isClosedBy(text: String): Boolean {
            val trimmed = text.trimStart()
            val runLength = trimmed.takeWhile { each -> each == character }.length
            return runLength >= length && trimmed.drop(runLength).isBlank()
        }
    }

    private data class MarkdownLine(val text: String, val insideFence: Boolean) {
        /** 0열의 `#` 제목이면 그 단계(1~6), 아니면(울타리 안 포함) null. */
        fun headingLevel(): Int? {
            if (insideFence) return null
            return HEADING.matchAt(text, 0)?.groupValues?.get(1)?.length
        }

        fun isQuestionSectionHeading(): Boolean = headingLevel() == 2 && text.drop(2).trim() == "질문"

        /** 0열의 `N. ` 번호 줄이면 그 번호, 아니면(울타리 안 포함) null. */
        fun questionNumber(): Int? {
            if (insideFence) return null
            return QUESTION_NUMBER.matchAt(text, 0)?.groupValues?.get(1)?.toInt()
        }
    }

    private val HEADING = Regex("""(#{1,6})(?:[ \t]|$)""")
    private val QUESTION_NUMBER = Regex("""(\d{1,9})\. """)
    private val ANSWER_HEADING = Regex("""###[ \t]+(\d{1,9})\.(?:[ \t]+(.*))?""")
}

sealed interface QuizSplitResult {
    /** 쪼개기 성공. [questions] · [answers] 는 파일에 나온 차례 그대로. */
    data class Split(
        val questions: List<QuizQuestion>,
        val answers: List<QuizAnswer>,
    ) : QuizSplitResult {
        /** 그 번호의 정답 하나. 없는 번호면 null. */
        fun answerOf(number: Int): QuizAnswer? = answers.firstOrNull { answer -> answer.number == number }
    }

    /** 쪼갤 수 없음. [numbers] 는 문제가 된 번호(겹친 번호 · 모자란 번호 · 남는 번호, 오름차순) — 해당 없으면 빈 목록. */
    data class Unsplittable(
        val reason: UnsplittableReason,
        val numbers: List<Int> = emptyList(),
    ) : QuizSplitResult
}

/**
 * 질문 하나.
 * @property text 번호 줄부터 다음 번호 줄 · 다음 제목 줄 앞까지의 글(끝의 공백 · 빈 줄을 뺌, 줄바꿈은 `\n`).
 * @property group 이 질문이 든 묶음 제목(예 `A. 과제` — `#` 과 앞뒤 공백을 뺀 것). 묶음 제목이 없으면 null.
 * @property hash [QuizSplitter.questionHash] 로 낸 [text] 의 해시(묶음 제목은 넣지 않는다).
 */
data class QuizQuestion(
    val number: Int,
    val group: String?,
    val text: String,
    val hash: String,
)

/**
 * 정답 하나.
 * @property title `### N.` 제목 줄에서 번호 뒤의 글(앞뒤 공백을 뺌). 없으면 빈 문자열.
 * @property text 제목 줄 아래부터 다음 1~3단계 제목 줄(`#` · `##` · `###`) 앞까지의 글 — 앞뒤의 빈 줄과 끝의 공백을 뺌, 줄바꿈은 `\n`.
 *   `####` 이하의 하위 제목 · 코드 울타리 안의 줄은 정답을 자르지 않는다.
 */
data class QuizAnswer(
    val number: Int,
    val title: String,
    val text: String,
)

enum class UnsplittableReason {
    /** 질문 파일에 `## 질문` 절이 없다. */
    NO_QUESTION_SECTION,
    /** `## 질문` 절에 번호 질문이 하나도 없다. */
    NO_NUMBERED_QUESTION,
    /** 같은 번호의 질문이 둘 이상. */
    DUPLICATE_QUESTION_NUMBER,
    /** 같은 번호의 정답(`### N.`)이 둘 이상. */
    DUPLICATE_ANSWER_NUMBER,
    /** 정답이 없는 질문 번호가 있다(정답이 모자람). */
    MISSING_ANSWER,
    /** 질문이 없는 정답 번호가 있다(정답이 남음). */
    EXTRA_ANSWER,
}
