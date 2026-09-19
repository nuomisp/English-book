package com.nuomisp.englishbook.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** Small, deterministic interval scheduler; it is deliberately not advertised as FSRS. */
object ReviewScheduler {
    private const val DAY_MS = 86_400_000L

    fun next(wordId: String, previous: WordProgress?, rating: ReviewRating, now: Long): WordProgress {
        require(previous == null || previous.wordId == wordId)
        val oldInterval = previous?.intervalDays ?: 0.0
        val interval = when (rating) {
            ReviewRating.AGAIN -> 10.0 / (24.0 * 60.0)
            ReviewRating.HARD -> max(0.25, oldInterval * 1.2).coerceAtMost(90.0)
            ReviewRating.GOOD -> if (oldInterval < 1.0) 1.0 else (oldInterval * 2.2).coerceAtMost(180.0)
            ReviewRating.EASY -> if (oldInterval < 1.0) 3.0 else (oldInterval * 3.0).coerceAtMost(365.0)
        }
        return WordProgress(
            wordId = wordId,
            nextReviewAt = now + (interval * DAY_MS).toLong(),
            intervalDays = interval,
            correctStreak = if (rating == ReviewRating.GOOD || rating == ReviewRating.EASY)
                (previous?.correctStreak ?: 0) + 1 else 0,
            attempts = (previous?.attempts ?: 0) + 1,
            lapses = (previous?.lapses ?: 0) + if (rating == ReviewRating.AGAIN) 1 else 0,
            lastRating = rating,
            lastReviewedAt = now,
        )
    }
}

/** Ignore case, presentation punctuation and whitespace, but never omit or reorder words. */
object AnswerChecker {
    private val tokens = Regex("[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)*")

    fun normalized(text: String): String = tokens.findAll(
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace('’', '\'').replace('‘', '\'').lowercase(Locale.ROOT),
    ).joinToString(" ") { it.value }

    fun matches(answer: String, expected: String): Boolean {
        val normalizedAnswer = normalized(answer)
        return normalizedAnswer.isNotEmpty() && normalizedAnswer == normalized(expected)
    }
}
