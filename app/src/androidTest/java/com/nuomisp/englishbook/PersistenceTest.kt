package com.nuomisp.englishbook

import androidx.test.platform.app.InstrumentationRegistry
import com.nuomisp.englishbook.data.LearningRepository
import com.nuomisp.englishbook.data.ReviewRating
import com.nuomisp.englishbook.services.ApiSettings
import com.nuomisp.englishbook.services.SecureSettingsStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersistenceTest {
    @Test(timeout = 60_000) fun learningBackupRoundTrips_andInvalidRestorePreservesData() {
        val repository=LearningRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val initial=repository.exportBackup()
        try {
            val word=repository.words().last()
            assertTrue(repository.recordSpelling(word.id,word.word))
            repository.addChatMessage("user","请多用简单例句")
            repository.saveMemorySummary("用户喜欢简单例句。")
            val saved=repository.exportBackup()
            val expected=repository.progress(word.id)
            repository.rateWord(word.id,ReviewRating.AGAIN)
            repository.clearChats()
            repository.importBackup(saved)
            assertEquals(expected,repository.progress(word.id))
            assertEquals("用户喜欢简单例句。",repository.memorySummary())
            assertEquals("请多用简单例句",repository.chatMessages(1).last().content)
            val malformed=JSONObject(saved).put("version",99).toString()
            assertTrue(runCatching{repository.importBackup(malformed)}.isFailure)
            assertEquals(expected,repository.progress(word.id))
        } finally { repository.importBackup(initial) }
    }
    @Test(timeout = 60_000) fun settingsRoundTripThroughKeystore_withoutPlaintextInPreferencesOrBackup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SecureSettingsStore(context)
        val initial=store.load()
        try {
            val value=ApiSettings(baseUrl="https://example.com/v1",apiKey="non-real-test-key-englishbook",chatModel="test-model",remindersEnabled=false)
            store.save(value)
            assertEquals(value,SecureSettingsStore(context).load())
            val xml=File(context.applicationInfo.dataDir,"shared_prefs/secure_api_settings.xml").readText()
            assertFalse(xml.contains(value.apiKey))
            assertFalse(LearningRepository.get(context).exportBackup().contains(value.apiKey))
        } finally { store.save(initial) }
    }
}
