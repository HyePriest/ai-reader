package com.jknl.englishreader.screens

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
        val marks = ReadingMarks(context)
        marks.markOpened(saved[3].id, 5); marks.markOpened(saved[5].id, 9); marks.setFavorite(saved[5].id, true)
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ArticleHome(shelf, store, marks = marks, onRead = {}, onOpenText = {}, onImport = {}, onSettings = {}, onNotebook = {}, onDelete = {})
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

    private fun examPart(title: String): Book {
        val dir = System.getenv("EXAM_DIR")
        assumeTrue(out != null && dir != null)
        return ExamPaper.read(File(dir!!, "2024-eng1.md").readText()).first { it.title == title }
    }

    private fun renderReader(book: Book, name: String) {
        val store = LibraryStore(context)
        store.save(book)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(book, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {}, initialExamOpen = true)
            } }
        }
        shoot(name)
    }

    @Test fun clozePanel() {
        val cloze = examPart("完形填空")
        val answers = ExamAnswers(context)
        "BCBAB".forEachIndexed { i, c -> answers.choose(cloze.id, i + 1, c) }
        renderReader(cloze, "exam-cloze")
    }

    @Test fun clozeSubmitted() {
        val cloze = examPart("完形填空")
        val answers = ExamAnswers(context)
        answers.setKey(cloze.collection, AnswerKey.parse("1-5 DCBAB"))
        "BCBAB".forEachIndexed { i, c -> answers.choose(cloze.id, i + 1, c) }
        answers.setSubmitted(cloze.id, true)
        renderReader(cloze, "exam-cloze-submitted")
    }

    @Test fun readingPanel() {
        val text = examPart("阅读 Text 1")
        ExamAnswers(context).choose(text.id, 21, 'D')
        renderReader(text, "exam-reading")
    }

    @Test fun aiConversation() {
        assumeTrue(out != null)
        val book = Articles.fromText("Title here\n\nFirst paragraph says one thing. It goes on.\n\nThe last paragraph concludes.")
        val q = ReadingQuestions.wholeArticle(book)
        val answer = """
            ## 全文大意
            作者认为**廉价技术**比复杂技术更能改变世界，钉子就是一个例子。

            ### 逐段要点
            1. 罗马人埋下一百万颗钉子：说明铁很**珍贵**。
            2. 弗吉尼亚人烧房取钉：钉子*稀缺*且昂贵。
            - 关键句：`it's the cheap technologies that change the world`

            | 段落 | 作用 |
            |---|---|
            | 1–2 | 引出话题 |
            | 3–4 | 举例论证 |

            > 注意：第 5 段是作者的核心观点。
        """.trimIndent()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ReadingQuestionContent(q, ChatThread.from(book.id, q).copy(exchanges = listOf(ReadingExchange("全文大意是什么？", answer))),
                    pending = "作者的态度是什么？", error = null, highlight = -1,
                    question = "", onQuestion = {}, onSend = {}, onStop = {}, onClear = {}, onUseArticle = {}, onReselect = {}, onAppend = {}, onClose = {})
            } }
        }
        shoot("ai-conversation")
    }

    @Test fun readingNoKey() {
        val text = examPart("阅读 Text 2")
        val answers = ExamAnswers(context)
        "ABDAB".forEachIndexed { i, c -> answers.choose(text.id, 26 + i, c) }
        answers.setSubmitted(text.id, true)
        renderReader(text, "exam-no-key")
    }

    @Test fun aiPassage() {
        assumeTrue(out != null)
        val book = Articles.fromText("Title here\n\nThe biomedical engineer, based at the Italian Institute of Technology in Genoa, has devised an assortment of nanotechnologies.\n\nMore text.")
        val q = ReadingQuestions.selection(book, listOf(PassageRange(0, 1, 0, 60)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ReadingQuestionContent(q, ChatThread.from(book.id, q), pending = null, error = null, highlight = -1,
                    question = "", onQuestion = {}, onSend = {}, onStop = {}, onClear = {}, onUseArticle = {}, onReselect = {}, onAppend = {}, onClose = {})
            } }
        }
        shoot("ai-passage")
    }

    @Test fun cartoon() {
        assumeTrue(out != null)
        val imported = issue()
        val store = LibraryStore(context)
        val a = imported.articles.first { it.title.startsWith("Cartoon") }
        store.save(a); imported.images[a.id]?.let { store.saveImage(a.id, it) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(a, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {})
            } }
        }
        shoot("cartoon")
    }

    @Test fun findBar() {
        assumeTrue(out != null)
        val book = article()
        val store = LibraryStore(context)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Reader(book, store, ReaderSettings(), onBack = {}, onSettings = {}, onUpdate = {}, initialFind = "the")
            } }
        }
        shoot("find")
    }

    @Test fun chatMenu() {
        assumeTrue(out != null)
        val book = Articles.fromText("Title here\n\nThe biomedical engineer, based at the Italian Institute of Technology in Genoa, has devised an assortment of nanotechnologies.\n\nMore text.")
        val q = ReadingQuestions.selection(book, listOf(PassageRange(0, 1, 0, 60)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ReadingQuestionContent(q, ChatThread.from(book.id, q).copy(exchanges = listOf(ReadingExchange("翻译这段并标出生词", "## 译文\n这位生物医学工程师……\n\n## 生词\n- **assortment** n. 各种各样"))),
                    pending = null, error = null, highlight = 0,
                    question = "", onQuestion = {}, onSend = {}, onStop = {}, onClear = {}, onUseArticle = {}, onReselect = {}, onAppend = {}, onClose = {})
            } }
        }
        compose.onNodeWithContentDescription("更多").performClick()
        shoot("chat-menu")
    }

    @Test fun menuRows() {
        assumeTrue(out != null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EnglishReaderTheme("paper") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                        androidx.compose.foundation.layout.Column(Modifier.width(240.dp).padding(vertical = 8.dp)) {
                            MenuRow("doc", "参考全文", checked = true) {}
                            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            MenuRow("select", "重新选文") {}
                            MenuRow("plus", "追加选文") {}
                            MenuRow("clock", "问答记录") {}
                            MenuRow("edit", "常用问题") {}
                            MenuRow("trash", "清空对话", danger = true) {}
                        }
                    }
                }
            } }
        }
        shoot("menu-rows")
    }
}
