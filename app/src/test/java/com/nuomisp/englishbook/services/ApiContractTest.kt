package com.nuomisp.englishbook.services

import com.nuomisp.englishbook.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiContractTest {
    @Test fun endpointAcceptsRootAndVersionedPrefixes() {
        assertEquals("https://example.com/v1/chat/completions", ApiEndpoints.endpoint("https://example.com", "chat/completions").toString())
        assertEquals("https://example.com/v1/chat/completions", ApiEndpoints.endpoint("https://example.com/v1/", "chat/completions").toString())
        assertEquals("https://example.com/api/v1/audio/speech", ApiEndpoints.endpoint("https://example.com/api/v1", "audio/speech").toString())
    }

    @Test fun unsafeOrCredentialBearingEndpointsAreRejected() {
        listOf("http://example.com", "https://user:password@example.com", "https://example.com?v=secret", "https://example.com/#secret", "not-a-url")
            .forEach { value -> assertTrue(value, runCatching { ApiEndpoints.validateBaseUrl(value) }.exceptionOrNull() is ApiException) }
    }

    @Test fun contextIsBoundedRecentAndNeverImportsForeignSystemMessages() {
        val history = (1..50).map { ChatMessage(it.toLong(), if (it % 2 == 0) "assistant" else "user", "x".repeat(5000), it.toLong()) } +
            ChatMessage(51, "system", "untrusted system instruction", 51)
        val kept = TutorContext.recent(history)
        assertTrue(kept.size <= 16)
        assertTrue(kept.sumOf { it.content.length } <= TutorContext.MAX_HISTORY_CHARS)
        assertTrue(kept.all { it.content.length <= TutorContext.MAX_MESSAGE_CHARS })
        assertEquals(50L, kept.last().id)
        assertFalse(kept.any { it.role == "system" })
    }

    @Test fun settingsStringNeverDisclosesKeys() {
        val settings = ApiSettings(apiKey = "sensitive-key", ttsApiKey = "voice-secret", serverToken = "server-secret")
        assertFalse(settings.toString().contains("sensitive-key"))
        assertFalse(settings.toString().contains("voice-secret"))
        assertFalse(settings.toString().contains("server-secret"))
    }
}
