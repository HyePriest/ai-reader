package com.jknl.englishreader

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.AnnotationDialog
import com.jknl.englishreader.ui.ImportGuideDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AnnotationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun annotationsPersistInBothReadersAndOpenInSeparateDialog() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-annotations-${System.nanoTime()}"
        val root = File(base.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences("$name-$n", mode)
        }
        try {
            val book = BookFiles.read("""{"format":"englishreader.book.v1","title":"Rain","chapters":[{"title":"One","paragraphs":[{"sentences":[{"en":"The rain stopped.","zh":"雨停了。","annotations":[{"quote":"rain","text":"这是随书注释。","title":"用词","source":"AI 注释"}]}]}]}]}""".byteInputStream())
            val store = LibraryStore(context)
            store.save(book)
            val position = ReadingPosition(0, 1, 4)
            store.savePosition(book, position)
            val reopened = LibraryStore(context).open(book.id)
            assertEquals(book, reopened)
            assertEquals(book, store.decode(store.encode(book)))
            assertEquals(position, store.position(reopened))
            var open by mutableStateOf(true)
            compose.setContent { MaterialTheme { if (open) AnnotationDialog(reopened.chapters[0].paragraphs[1], 0) { open = false } } }
            compose.onNodeWithTag("annotation-panel").assertIsDisplayed()
            compose.onNodeWithText("rain").assertDoesNotExist()
            compose.onNodeWithText("用词").assertDoesNotExist()
            compose.onNodeWithText("来源：AI 注释").assertDoesNotExist()
            compose.onNodeWithText("这是随书注释。").assertIsDisplayed()
            compose.onNodeWithTag("translation-panel").assertDoesNotExist()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("annotation-panel").assertDoesNotExist()
        } finally {
            require(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
            base.deleteSharedPreferences("$name-reader")
        }
    }

    @Test fun guideOffersIndependentAiTranslationPrompt() {
        compose.setContent { MaterialTheme { ImportGuideDialog {} } }
        compose.onNodeWithText("AI 翻译", substring = false).performClick()
        compose.onNodeWithText("无需我另外寻找中文译本", substring = true).assertExists()
        compose.onNodeWithText("复制完整提示词").assertIsDisplayed()
        compose.onNodeWithText("纯中文", substring = false).performClick()
        compose.onNodeWithText("只提取、校对提取结果和整理结构", substring = true).assertExists()
    }
}
