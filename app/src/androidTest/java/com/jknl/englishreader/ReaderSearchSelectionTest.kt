package com.jknl.englishreader

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.io.File

class ReaderSearchSelectionTest {
    @Test fun importedCoverTocAndNotesSurviveReopenAndNoteOpensWithoutJump() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-epub-structure-${System.nanoTime()}"
        val root = File(base.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences("$name-$n", mode)
        }
        var visible by mutableStateOf(true)
        try {
            val cover = android.graphics.Bitmap.createBitmap(10, 20, android.graphics.Bitmap.Config.ARGB_8888)
            cover.eraseColor(android.graphics.Color.RED)
            val image = java.io.ByteArrayOutputStream()
            cover.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, image); cover.recycle()
            val source = File(root, "book.epub")
            java.util.zip.ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(path: String, bytes: ByteArray) { zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
                entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray())
                entry("book.opf", """<package><metadata><title>资源测试</title></metadata><manifest><item id="main" href="main.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/></manifest><spine><itemref idref="main"/><itemref idref="notes" linear="no"/></spine></package>""".toByteArray())
                entry("main.xhtml", """<h1 id="intro">前言</h1><p>原文<a epub:type="noteref" href="notes.xhtml#fn1">①</a>继续。</p>""".toByteArray())
                entry("notes.xhtml", """<h1>注释</h1><aside id="fn1" epub:type="footnote"><p>文后的完整注释。</p></aside>""".toByteArray())
                entry("nav.xhtml", """<nav epub:type="toc"><ol><li><a href="main.xhtml#intro">前言</a></li><li><a href="notes.xhtml">注释</a></li></ol></nav>""".toByteArray())
                entry("cover.png", image.toByteArray())
            }
            val store = LibraryStore(context)
            val book = store.import(android.net.Uri.fromFile(source))
            store.save(book)
            val reopened = LibraryStore(context).open(book.id)
            assertEquals(book, reopened); assertEquals(book, store.decode(store.encode(book)))
            assertTrue(BookCovers(context).file(book.id).exists())
            assertNotNull(BookCovers(context).load(book.id))
            assertEquals(listOf("前言", "注释"), reopened.contents.map { it.title })
            compose.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme {
                if (visible) Reader(reopened, store, ReaderSettings(), {}, {}, trackingEnabled = false, onUpdate = {})
            } } }
            compose.waitForIdle()
            val node = compose.onNodeWithTag("paragraph-0-1")
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val before = node.fetchSemanticsNode().boundsInRoot.top
            val glyph = results.single().getBoundingBox(2)
            node.performTouchInput { click(Offset(glyph.right - 1f, glyph.center.y)) }
            compose.onNodeWithTag("annotation-panel").assertIsDisplayed()
            compose.onNode(hasText("文后的完整注释。") and hasAnyAncestor(hasTestTag("annotation-panel"))).assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("annotation-panel").assertDoesNotExist()
            assertEquals(before, node.fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithContentDescription("目录").performClick()
            compose.onNode(hasText("前言") and hasAnyAncestor(hasTestTag("chapter-dialog"))).assertIsDisplayed()
            compose.onNode(hasText("注释") and hasAnyAncestor(hasTestTag("chapter-dialog"))).assertIsDisplayed()
            compose.onNodeWithText("01").assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible = false }; compose.waitForIdle()
            require(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively(); base.deleteSharedPreferences("$name-reader")
        }
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepTestVisible() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (android.os.Build.VERSION.SDK_INT >= 27) { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true) }
        }
    }

    @Test fun singleCharacterTapAndSmallDragKeepOriginalStart() {
        val row = ReadingRow(0, 0, Paragraph("p", "春风又绿江南岸 hello"))
        val layouts = mutableStateMapOf<String, PassageLayout>()
        var textLayout: TextLayoutResult? = null
        var textBounds: Rect? = null
        var picked = emptyList<PassageRange>()
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                Text(row.paragraph.text, fontSize = 28.sp,
                    onTextLayout = { l -> textLayout = l; textBounds?.let { layouts[row.key] = PassageLayout(row, it, l) } },
                    modifier = Modifier.padding(top = 150.dp, start = 25.dp).testTag("fixture")
                        .onGloballyPositioned { c -> textBounds = c.boundsInRoot(); textLayout?.let { l -> layouts[row.key] = PassageLayout(row, c.boundsInRoot(), l) } })
                PassageOverlay(layouts, listOf(row), {}, { picked = it }, {})
            }
        } }
        compose.waitForIdle()
        val p = layouts.values.single()
        val overlay = compose.onNodeWithTag("ai-selection-overlay")
        val origin = overlay.fetchSemanticsNode().boundsInRoot.topLeft
        val glyph = p.layout.getBoundingBox(2).translate(p.bounds.topLeft - origin)
        for (fraction in listOf(.2f, .8f)) {
            overlay.performTouchInput { click(Offset(glyph.left + glyph.width * fraction, glyph.center.y)) }
            compose.runOnIdle { assertEquals(listOf(PassageRange(0, 0, 2, 3)), picked) }
        }
        overlay.performTouchInput {
            down(Offset(glyph.left + 1, glyph.center.y))
            moveTo(Offset(glyph.right - 1, glyph.center.y), 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(PassageRange(0, 0, 2, 3)), picked) }
        val letter = p.layout.getBoundingBox(9).translate(p.bounds.topLeft - origin)
        overlay.performTouchInput { click(letter.center) }
        compose.runOnIdle { assertEquals("hello", row.paragraph.text.substring(picked.single().start, picked.single().end)) }
        overlay.performTouchInput {
            down(Offset(letter.left + 1, letter.center.y))
            moveTo(Offset(letter.right - 1, letter.center.y), 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(PassageRange(0, 0, 9, 10)), picked) }
    }

    @Test fun searchJumpsAcrossChaptersToMatchingLine() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-search-${System.nanoTime()}"
        val root = File(base.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences("$name-$n", mode)
        }
        val longText = (1..50).joinToString("\n") { "这是第${it}行普通正文。" } + "\n唯一检索词在这里。"
        val book = Book("qa-search", "搜索测试", "", listOf(
            Chapter("one", "第一章", listOf(Paragraph("a", "The Rain stopped. rain again.")), emptyList()),
            Chapter("two", "第二章", listOf(Paragraph("b", longText)), emptyList())), 0)
        var visible by mutableStateOf(true)
        try {
            compose.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme {
                if (visible) Reader(book, LibraryStore(context), ReaderSettings(), {}, {}, trackingEnabled = false, onUpdate = {})
            } } }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("reader-toolbar").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithContentDescription("搜索本书").performClick()
            compose.onNodeWithTag("reader-search-input").performTextInput("RAIN")
            compose.waitUntil(10000) { compose.onAllNodesWithText("找到 2 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-search-input").performTextReplacement("唯一检索词")
            compose.waitUntil(10000) { compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("唯一检索词", substring = true) and hasAnyAncestor(hasTestTag("reader-search-results"))).performClick()
            compose.onNodeWithTag("reader-search-input").assertDoesNotExist()
            compose.waitForIdle()
            val node = compose.onNodeWithTag("paragraph-1-0")
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            val offset = longText.indexOf("唯一检索词")
            val glyph = layout.getBoundingBox(offset).translate(node.fetchSemanticsNode().positionInRoot)
            val toolbar = compose.onNodeWithTag("reader-toolbar").fetchSemanticsNode().boundsInRoot
            assertTrue("Match must be visible below toolbar", glyph.top >= toolbar.bottom)
            assertTrue(glyph.bottom < compose.onNodeWithTag("reading-body").fetchSemanticsNode().boundsInRoot.bottom)
            assertTrue(layout.layoutInput.text.spanStyles.any { it.start == offset && it.end == offset + 5 })
            compose.onNodeWithContentDescription("搜索本书").performClick()
            compose.onNodeWithTag("reader-search-input").performTextInput("不存在的词")
            compose.waitUntil(10000) { compose.onAllNodesWithText("没有找到匹配的正文").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            require(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively(); base.deleteSharedPreferences("$name-reader")
        }
    }
}
