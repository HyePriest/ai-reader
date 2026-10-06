package com.jknl.englishreader

import android.content.ContextWrapper
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.BookImportPreview
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EbookFormatsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepTestVisible() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun textPdfRetainsPageOrderAndText() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val bytes = pdf(false)
        val book = BookFiles.read(bytes.inputStream(), "Sample.pdf")
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].paragraphs.any { it.text.contains("First page: the rain stopped.") })
        assertTrue(book.chapters[1].paragraphs.any { it.text.contains("Second page: a bird flew.") })
        assertFalse(book.chapters.flatMap { it.paragraphs }.any { it.pdfPage != null })
    }

    @Test fun scanPdfImportsPersistsAndDisplaysOriginalPage() {
        val isolated = object : ContextWrapper(context) {
            private val root = File(context.cacheDir, "ebook-qa-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getFilesDir(): File = root
        }
        val source = File(isolated.cacheDir, "scan-${UUID.randomUUID()}.pdf").apply { writeBytes(pdf(true)) }
        val store = LibraryStore(isolated)
        val book = store.import(Uri.fromFile(source))
        assertTrue(File(isolated.filesDir, "library/${book.id}.pdf").exists())
        assertNotNull(BookCovers(isolated).load(book.id))
        assertEquals(listOf(0, 1), book.chapters.flatMap { it.paragraphs }.map { it.pdfPage })
        assertEquals(book, store.decode(store.encode(book)))
        store.save(book)
        assertEquals(book, LibraryStore(isolated).open(book.id))
        compose.setContent { CompositionLocalProvider(LocalContext provides isolated) {
            MaterialTheme { BookImportPreview(book, {}, {}) }
        } }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 1 页，点击放大").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Exception) { compose.onRoot(useUnmergedTree = true).printToLog("PdfQA"); throw e }
        compose.onNodeWithContentDescription("第 1 页，点击放大").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
    }

    @Test fun realMobiPalmDocHuffAndKf8Corpus() {
        val directory = File(context.getExternalFilesDir(null), "qa-ebook-formats")
        val names = listOf("sample-cp1252.mobi", "sample-unicode-huffdic.mobi", "sample-unicode-uncompressed.mobi", "sample-obfuscated-fonts.mobi", "sample-textread.mobi", "sample-ncx.mobi")
        assumeTrue("Supply the upstream libmobi fixtures using adb", names.all { File(directory, it).exists() })
        val store = LibraryStore(context)
        names.forEach { name ->
            var coverSize = 0
            val book = File(directory, name).inputStream().use { BookFiles.read(it, name) { _, cover -> coverSize = cover.size } }
            val text = book.chapters.flatMap { it.paragraphs }.joinToString("\n") { it.text }
            assertTrue("$name text missing", text.length > if (name == "sample-ncx.mobi") 20 else 1000)
            assertFalse("$name has decoding errors", text.contains('\uFFFD'))
            assertEquals(book, store.decode(store.encode(book)))
            if (name == "sample-cp1252.mobi") assertTrue("Kindle cover resource missing", coverSize > 0)
            if (name == "sample-ncx.mobi") {
                assertEquals(listOf("Test chapter 1", "Test chapter 2", "Test subchapter 2-1", "Test subchapter 2-2"), book.contents.map { it.title })
                assertEquals(listOf(0, 0, 1, 1), book.contents.map { it.depth })
                android.util.Log.i("EbookQA", "NCX entries: ${book.contents}")
            }
            android.util.Log.i("EbookQA", "$name chapters=${book.chapters.size} characters=${text.length} contents=${book.contents.size} coverBytes=$coverSize title=${book.title}")
        }
        listOf("sample-drm-v1.mobi", "sample-drm_pidLTKULBB^5V-v2.mobi").forEach { name ->
            val encrypted = File(directory, name)
            if (encrypted.exists()) {
                val error = assertThrows(IllegalArgumentException::class.java) { encrypted.inputStream().use { BookFiles.read(it, name) } }
                assertTrue(error.message.orEmpty().contains("DRM"))
            }
        }
    }

    @Test fun pdfBookmarksKeepFrontMatterLabelsAndHierarchy() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val output = ByteArrayOutputStream()
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf(false)).use { doc ->
            val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val intro = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
                title = "前言"; setDestination(doc.getPage(0))
            }
            val chapter = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
                title = "第一章"; setDestination(doc.getPage(1))
            }
            val section = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
                title = "小节"; setDestination(doc.getPage(1))
            }
            outline.addLast(intro); outline.addLast(chapter); chapter.addLast(section)
            doc.save(output)
        }
        val book = BookFiles.read(output.toByteArray().inputStream(), "book.pdf")
        assertEquals(listOf("前言", "第一章", "小节"), book.contents.map { it.title })
        assertEquals(listOf(0, 1, 1), book.contents.map { it.chapter })
        assertEquals(listOf(0, 0, 1), book.contents.map { it.depth })
        val store = LibraryStore(context)
        assertEquals(book, store.decode(store.encode(book)))
    }

    private fun pdf(scan: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val pdf = PdfDocument()
        try {
            repeat(2) { i ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, i + 1).create())
                val paint = Paint().apply { textSize = 16f }
                if (scan) page.canvas.drawRect(40f, 40f, 300f, 500f, paint)
                else page.canvas.drawText(if (i == 0) "First page: the rain stopped." else "Second page: a bird flew.", 30f, 80f, paint)
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally { pdf.close() }
        return output.toByteArray()
    }
}
