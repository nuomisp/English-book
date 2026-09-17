package com.nuomisp.englishbook.data

import org.junit.Assert.*
import org.junit.Test

class ReviewSchedulerTest {
    private val now = 1_700_000_000_000L

    @Test fun `first successful recall is due tomorrow and is not mastery`() {
        val result = ReviewScheduler.next("learn", null, ReviewRating.GOOD, now)
        assertEquals(now + 86_400_000L, result.nextReviewAt)
        assertEquals(1, result.attempts)
        assertEquals(1, result.correctStreak)
        assertFalse(result.isMastered)
    }

    @Test fun `forgetting a stable word schedules a short retry and removes mastery`() {
        val previous = WordProgress("learn", now, 30.0, 6, 8, 1, ReviewRating.GOOD, now - 86_400_000L)
        assertTrue(previous.isMastered)
        val result = ReviewScheduler.next("learn", previous, ReviewRating.AGAIN, now)
        assertEquals(now + 600_000L, result.nextReviewAt)
        assertEquals(0, result.correctStreak)
        assertEquals(2, result.lapses)
        assertEquals(9, result.attempts)
        assertFalse(result.isMastered)
    }

    @Test fun `difficult first recall retries the same day without counting a lapse`() {
        val result = ReviewScheduler.next("learn", null, ReviewRating.HARD, now)
        assertEquals(now + 21_600_000L, result.nextReviewAt)
        assertEquals(0, result.lapses)
        assertEquals(0, result.correctStreak)
    }

    @Test fun `repeated successful reviews expand intervals and cap them`() {
        var progress: WordProgress? = null
        repeat(12) { progress = ReviewScheduler.next("learn", progress, ReviewRating.EASY, now) }
        assertEquals(365.0, progress!!.intervalDays, 0.0)
        assertTrue(progress!!.isMastered)
        assertEquals(12, progress!!.attempts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `progress from another word cannot be reused`() {
        val previous = ReviewScheduler.next("read", null, ReviewRating.GOOD, now)
        ReviewScheduler.next("learn", previous, ReviewRating.GOOD, now)
    }

    @Test fun `dictation accepts presentation differences but not missing words`() {
        assertTrue(AnswerChecker.matches("  I READ every day!  ", "I read every day."))
        assertFalse(AnswerChecker.matches("I read day", "I read every day."))
        assertFalse(AnswerChecker.matches("I every read day", "I read every day."))
        assertFalse(AnswerChecker.matches("", "I read every day."))
        assertFalse(AnswerChecker.matches("...", "I read every day."))
    }

    @Test fun `contractions preserve meaning and support typographic apostrophes`() {
        assertTrue(AnswerChecker.matches("I’m ready.", "I'm ready."))
        assertFalse(AnswerChecker.matches("I cant read", "I can't read."))
        assertFalse(AnswerChecker.matches("I can read", "I can't read."))
    }

    @Test fun `non English extra text is not silently discarded`() {
        assertFalse(AnswerChecker.matches("learn你好", "learn"))
        assertFalse(AnswerChecker.matches("learn 学习", "learn"))
        assertTrue(AnswerChecker.matches("ＬＥＡＲＮ", "learn"))
    }
}
