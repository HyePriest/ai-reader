package com.jknl.englishreader.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.Reader
import com.jknl.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opening the chat from a real reader must put the sheet on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w392dp-h850dp-xxhdpi")
class ReaderChatTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun askArticleOpensVisibleSheet() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val book = Articles.fromText("A title\n\n" + (1..30).joinToString("\n\n") { "Paragraph $it has some words in it for reading." })
        val store = LibraryStore(context)
        store.save(book)
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(book, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {})
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("ask-article").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("reading-question-sheet").assertIsDisplayed()
        compose.onNodeWithTag("reading-question-input").assertIsDisplayed()
    }

    @Test fun sheetFromPassageQuestionAlsoOpens() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val book = Articles.fromText("A title\n\n" + (1..30).joinToString("\n\n") { "Paragraph $it has some words in it for reading." })
        val store = LibraryStore(context)
        store.save(book)
        val thread = ChatThread.from(book.id, ReadingQuestions.selection(book, listOf(PassageRange(0, 2, 0, 9))))
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(book, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {}, initialThread = thread)
            } }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("reading-question-sheet").assertIsDisplayed()
    }
}
