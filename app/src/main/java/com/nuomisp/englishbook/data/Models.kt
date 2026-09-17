package com.nuomisp.englishbook.data

data class Word(
    val id: String,
    val word: String,
    val ipa: String,
    val meaning: String,
    val example: String,
    val exampleZh: String,
    val level: String,
)

enum class ReviewRating { AGAIN, HARD, GOOD, EASY }

data class WordProgress(
    val wordId: String,
    val nextReviewAt: Long,
    val intervalDays: Double,
    val correctStreak: Int,
    val attempts: Int,
    val lapses: Int,
    val lastRating: ReviewRating,
    val lastReviewedAt: Long,
) {
    val isMastered: Boolean get() = correctStreak >= 3 && intervalDays >= 21.0
}

data class DailyStats(
    val date: String,
    val newWords: Int = 0,
    val reviewedWords: Int = 0,
    val studyMinutes: Int = 0,
    val readingCompleted: Int = 0,
    val listeningAttempts: Int = 0,
    val listeningCorrect: Int = 0,
)

data class ReadingQuestion(
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
)

data class ReadingArticle(
    val id: String,
    val title: String,
    val level: String,
    val paragraphs: List<String>,
    val translation: String,
    val questions: List<ReadingQuestion>,
)

data class ReadingResult(val articleId: String, val correct: Int, val total: Int)

data class DictationSentence(
    val id: String,
    val text: String,
    val translation: String,
    val level: String,
)

data class DictationResult(val sentenceId: String, val isCorrect: Boolean, val expected: String)

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)
