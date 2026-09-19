package com.nuomisp.englishbook

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nuomisp.englishbook.data.LearningRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Runs on a real Android emulator, exercising native Compose controls and persistent SQLite state. */
class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun page(id: String) {
        compose.onNodeWithTag("menu").performClick()
        compose.onNodeWithTag("nav_$id").performClick()
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrument.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        // Keep captures outside app data so test-runner cleanup cannot remove them.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/englishbook-screenshots")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${File(directory,"$name.png").absolutePath} /sdcard/Download/englishbook-screenshots/$name.png")).use { it.readBytes() }
    }
    @Test(timeout = 120_000) fun learnOneWord_andNavigateNativeScreens() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("今天，也要进步一点。").fetchSemanticsNodes().isNotEmpty() }
        capture("01-home")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val before=LearningRepository.get(context).learnedCount()
        page("words")
        compose.onNodeWithTag("reveal_word").performScrollTo().performClick()
        compose.onNodeWithTag("rate_good").performScrollTo()
        capture("02-word")
        compose.onNodeWithTag("rate_good").performClick()
        compose.waitUntil(10_000) { LearningRepository.get(context).learnedCount()==before+1 }
        // An independent SQLite helper sees the same committed data, not a UI-only progress counter.
        assertEquals(before+1,LearningRepository(context).learnedCount())
        page("reading")
        compose.onNodeWithTag("reading_screen").assertIsDisplayed()
        capture("03-reading")
        page("listening")
        compose.onNodeWithTag("dictation_input").performScrollTo().performTextInput("A practice answer")
        capture("04-listening")
        page("settings")
        compose.onNodeWithText("API 密钥").assertExists()
        capture("05-settings")
        page("progress")
        capture("06-progress")
        page("home")
    }
}
