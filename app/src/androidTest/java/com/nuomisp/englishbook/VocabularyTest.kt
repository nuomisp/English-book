package com.nuomisp.englishbook

import androidx.test.platform.app.InstrumentationRegistry
import com.nuomisp.englishbook.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VocabularyTest {
    private val repository get()=LearningRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test(timeout=60_000) fun dictionaryHasAuditedCountsAndExplicitWordForms() {
        val words=repository.words()
        assertEquals(13806,words.size)
        assertEquals(3849,words.count{"cet4" in it.tags})
        assertEquals(1613,words.count{"zk" in it.tags})
        assertTrue(words.all{it.meaning.isNotBlank()})
        assertTrue(repository.lookup("studying").any{it.id=="study"})
        assertTrue(repository.lookup("went").any{it.id=="go"})
        assertEquals(40,repository.dictations().size)
        assertEquals("I learn ten words every morning.",repository.dictations().first().text)
    }
    @Test(timeout=60_000) fun savingDoesNotScore_enrollmentAndUndoPreserveProgress() {
        val initial=repository.exportBackup()
        try {
            val before=repository.dailyStats()
            val progress=repository.progress("learn")
            repository.saveCard("word","learn","learn","I learn English.","测试来源")
            assertEquals(before,repository.dailyStats())
            assertEquals(progress,repository.progress("learn"))
            repository.rateWord("learn",ReviewRating.GOOD)
            assertTrue(repository.undoReview())
            assertEquals(progress,repository.progress("learn"))
            assertEquals(before,repository.dailyStats())
            repository.setWordStatus("learn","familiar")
            assertFalse(repository.studyQueue().any{it.id=="learn"})
            assertFalse(repository.weakWords().any{it.id=="learn"})
            assertEquals(before,repository.dailyStats())
            val backup=repository.exportBackup()
            repository.removeCard(repository.savedCards().first{it.wordId=="learn"}.id)
            repository.importBackup(backup)
            assertTrue(repository.savedCards().any{it.wordId=="learn"})
            assertEquals("familiar",repository.overrides()["learn"])
            repository.savePreferences(StudyPreferences("cet4",0))
            assertTrue(repository.studyQueue().all{repository.progress(it.id)!=null})
        } finally{repository.importBackup(initial)}
    }
    @Test(timeout=60_000) fun versionOneBackupsStillRestore() {
        val initial=repository.exportBackup()
        try {
            repository.rateWord("learn",ReviewRating.HARD)
            val expected=repository.progress("learn")
            val old=JSONObject(repository.exportBackup()).put("version",1)
            listOf("saved_cards","study_preferences","word_overrides").forEach{old.remove(it)}
            repository.importBackup(old.toString())
            assertEquals(expected,repository.progress("learn"))
            assertTrue(repository.savedCards().isEmpty())
            assertEquals(StudyPreferences(),repository.preferences())
        } finally{repository.importBackup(initial)}
    }
}
