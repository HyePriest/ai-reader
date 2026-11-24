package com.jknl.englishreader.screens

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.*
import com.jknl.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Writes PNGs of key screens to SHOT_DIR for design review. Skipped unless SHOT_DIR is set.
 * Uses the real Economist issue from ECONOMIST_EPUB when available.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w392dp-h850dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out: File? get() = System.getenv("SHOT_DIR")?.let { File(it).apply { mkdirs() } }

    private fun shoot(name: String) {
        // Background work (images, reading timers) runs on real threads; let it land, then draw frames.
        repeat(6) { Thread.sleep(150); compose.mainClock.advanceTimeBy(300) }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun issue(): EconomistIssue.Imported {
        val path = System.getenv("ECONOMIST_EPUB")
        assumeTrue(path != null && File(path).exists())
        return EconomistIssue.readWithImages(File(path!!).readBytes())!!
    }

    private fun article(palette: String = "paper"): Book {
        val imported = issue()
        val store = LibraryStore(context)
        val a = imported.articles.first { it.section == "Science & technology" }.copy(titleZh = "示例中文标题：科学与技术")
        store.save(a); imported.images[a.id]?.let { store.saveImage(a.id, it) }
        return a
    }

    @Test fun reader() {
        assumeTrue(out != null)
        val book = article()
        val store = LibraryStore(context)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(book, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {})
            } }
        }
        shoot("reader-paper")
    }

    @Test fun readerNight() {
        assumeTrue(out != null)
        val book = article()
        val store = LibraryStore(context)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("night") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(book, store, ReaderSettings(palette = "night"), onBack = {}, onSettings = {}, onUpdate = {})
            } }
        }
        shoot("reader-night")
    }

    @Test fun home() {
        assumeTrue(out != null)
        val imported = issue()
        val store = LibraryStore(context)
        val saved = imported.articles.take(12).mapIndexed { i, a -> a.copy(titleZh = if (i % 3 == 2) "" else "中文标题示例 ${i + 1}") }
        saved.forEach { store.save(it); imported.images[it.id]?.let { b -> store.saveImage(it.id, b) } }
        val single = Articles.fromText("Why cities matter\n\nCities are where most people now live. They are engines of growth.")
        store.save(single)
        val shelf = (saved + single).map { ShelfBook.from(it) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ArticleHome(shelf, store, onRead = {}, onOpenText = {}, onImport = {}, onSettings = {}, onNotebook = {}, onDelete = {})
            } }
        }
        shoot("home")
    }

    @Test fun issueScreen() {
        assumeTrue(out != null)
        val imported = issue()
        val store = LibraryStore(context)
        val saved = imported.articles.take(14).mapIndexed { i, a -> a.copy(titleZh = if (i % 4 == 3) "" else "中文标题示例：第 ${i + 1} 篇文章") }
        saved.forEach { store.save(it); imported.images[it.id]?.let { b -> store.saveImage(it.id, b) } }
        val shelf = saved.map { ShelfBook.from(it) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                IssueScreen(shelf.first().collection, shelf, store, onBack = {}, onRead = {}, onDelete = {})
            } }
        }
        shoot("issue")
    }

    @Test fun examReader() {
        val dir = System.getenv("EXAM_DIR")
        assumeTrue(out != null && dir != null)
        val part = ExamPaper.read(File(dir!!, "2024-eng1.md").readText()).first { it.title == "阅读 Text 1" }
        val store = LibraryStore(context)
        store.save(part)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(part, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {})
            } }
        }
        shoot("exam-reader")
    }
}
