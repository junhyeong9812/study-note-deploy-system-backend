package xyz.junproject.backend.quiz.domain

/**
 * cs 주제의 질문 파일(`1-question.md`)과 정답 파일(`3-answer.md`)을 질문 단위로 쪼개 같은 번호끼리 짝짓는다.
 * 뼈대 — 구현은 task 03a 의 구현 워커가 채운다(계약은 QuizSplitterTest).
 */
object QuizSplitter {
    /** 두 파일의 글을 받아 질문 단위로 쪼갠다. 쪼갤 수 없으면 [QuizSplitResult.Unsplittable] — 예외를 던지지 않는다. */
    fun split(questionMarkdown: String, answerMarkdown: String): QuizSplitResult = TODO("task 03a")

    /** 질문의 글에서 끝의 공백 · 빈 줄을 뺀 것으로 낸 해시. 같은 글이면 언제나 같은 값. */
    fun questionHash(questionText: String): String = TODO("task 03a")
}

sealed interface QuizSplitResult {
    /** 쪼개기 성공. [questions] · [answers] 는 파일에 나온 차례 그대로. */
    data class Split(
        val questions: List<QuizQuestion>,
        val answers: List<QuizAnswer>,
    ) : QuizSplitResult {
        /** 그 번호의 정답 하나. 없는 번호면 null. */
        fun answerOf(number: Int): QuizAnswer? = TODO("task 03a")
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
