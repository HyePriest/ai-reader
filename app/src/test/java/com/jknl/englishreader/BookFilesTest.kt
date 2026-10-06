package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookFilesTest {
    private val html = """<!DOCTYPE html><html><head><title>Original sample</title></head><body>
      <section class="chapter" id="chapter-1"><header class="chapter-head"><h2 data-title-zh="雨">Rain</h2></header>
      <div class="prose"><p id="c1p1">Rain <em>fell</em>. <span data-note="n1">Nora</span> waited.</p><pre id="poem">A bird
on a branch.</pre></div></section>
      <script>const TRANSLATIONS={"c1p1":["下雨了。",null],"poem":["一只鸟，在枝头。"]};
      const SENTENCE_BOUNDS={"c1p1":[[0,10],[11,23]]};
      const NOTES={"n1":{"term":"Nora","meaning":"人名；无需当作常用词背诵。"}};
      throw new Error('this script must never execute');</script></body></html>"""

    @Test fun readsLegacyBoundariesTranslationsNotesAndPoetryWithoutExecutingScripts() {
        val book = BookFiles.read(html.byteInputStream())
        val c = book.chapters.single()
        val p = c.paragraphs[1]
        assertEquals("Rain fell. Nora waited.", p.text)
        assertEquals(2, p.units.size)
        assertEquals("Nora", p.notes.single().term)
        assertEquals("fell", p.text.substring(p.marks.single().start, p.marks.single().end))
        assertEquals("下雨了。", c.translationAt(p.id, 2)?.chinese)
        assertNull(c.translationAt(p.id, 15))
        assertTrue(c.paragraphs.last().text.contains('\n'))
        assertEquals("部分配有中文", c.coverage())
    }

    @Test fun rejectsBadBoundariesAndMismatchedTranslationCounts() {
        assertThrows(IllegalArgumentException::class.java) { HtmlBookImporter().read(html.replace("[11,23]", "[11,100]")) }
        assertThrows(IllegalArgumentException::class.java) { HtmlBookImporter().read(html.replace("\"下雨了。\",null", "\"下雨了。\"")) }
    }

    @Test fun templatesOnlyFillChineseAndPreserveOtherContent() {
        val original = HtmlBookImporter().read(html)
        val root = JSONObject(TranslationFiles.exportChapter(original, 0))
        val p = root.getJSONArray("chapters").getJSONObject(0).getJSONArray("paragraphs").getJSONObject(0)
        assertEquals(1, p.getJSONArray("sentences").length())
        p.getJSONArray("sentences").getJSONObject(0).put("zh", "诺拉等着。")
        val result = TranslationFiles.prepare(original, root.toString())
        assertEquals(1, result.count)
        assertEquals(0, result.conflicts)
        assertEquals(original.id, result.book.id)
        assertEquals(original.chapters[0].paragraphs, result.book.chapters[0].paragraphs)
        assertEquals("诺拉等着。", result.book.chapters[0].translationAt("c1p1", 15)?.chinese)
        assertEquals("下雨了。", result.book.chapters[0].translationAt("c1p1", 2)?.chinese)
        assertEquals(1, TranslationFiles.prepare(result.book, root.toString()).conflicts)
        p.getJSONArray("sentences").getJSONObject(0).remove("en")
        p.remove("text")
        assertEquals(result.book, TranslationFiles.prepare(original, root.toString()).book)
        p.getJSONArray("sentences").getJSONObject(0).put("en", "Changed source.")
        assertThrows(IllegalArgumentException::class.java) { TranslationFiles.prepare(original, root.toString()) }
    }

    @Test fun newBookNeedsOnlyContentAndSupportsPartialChinese() {
        val book = BookFiles.readJson(JSONObject("""{"format":"englishreader.book.v1","title":"A small book","chapters":[{"id":"one","title":"Window","paragraphs":[{"id":"p1","sentences":[{"en":"The rain came.","zh":"雨来了。"},{"en":"She waited.","zh":null}]}]}]}"""))
        assertEquals("The rain came. She waited.", book.chapters.single().paragraphs[1].text)
        assertEquals("部分配有中文", book.chapters.single().coverage())
        val unfilled = TranslationFiles.exportChapter(book, 0)
        assertThrows(IllegalArgumentException::class.java) { TranslationFiles.prepare(book, unfilled) }
        assertThrows(IllegalArgumentException::class.java) { TranslationFiles.prepare(book, unfilled.replace(book.id, "wrong-book")) }
    }

    @Test fun sameSentenceTogglesOffAndDifferentSentenceSwitches() {
        val p = Paragraph("p", "One. Two.")
        val first = Selection(p, SourceRange("p", 0, 4), "One", null)
        val second = Selection(p, SourceRange("p", 5, 9), "Two", null)
        assertEquals(first, toggleSelection(null, first))
        assertNull(toggleSelection(first, first))
        assertEquals(second, toggleSelection(first, second))
    }

    @Test fun validateActualReadingLibraryWhenAvailable() {
        val path = System.getenv("ENGLISHREADER_HTML_DIR") ?: return
        val files = File(path).listFiles { f -> f.extension.lowercase() == "html" }!!.sortedBy { it.name }
        assertEquals(5, files.size)
        val report = files.map { file ->
            val book = file.inputStream().use(BookFiles::read)
            assertTrue(book.chapters.isNotEmpty())
            val translatedChapters = book.chapters.count { c -> c.alignments.any { it.ranges.any { r -> r.paragraph != "@title" } } }
            if (file.name.startsWith("苏菲")) { assertEquals(35, book.chapters.size); assertEquals(3, translatedChapters) }
            if (file.name.startsWith("爱丽丝")) assertEquals(12, book.chapters.size)
            if (file.name.startsWith("仿生人")) assertEquals(22, book.chapters.size)
            if (file.name.startsWith("Animal")) assertEquals(10, book.chapters.size)
            "${file.name}\tchapters=${book.chapters.size}\tparagraphs=${book.chapters.sumOf { it.paragraphs.size }}\ttranslations=${book.chapters.sumOf { it.alignments.size }}\tnotes=${book.chapters.sumOf { c -> c.paragraphs.sumOf { it.notes.size } }}\ttranslatedChapters=$translatedChapters"
        }
        val animal = files.filter { it.name.startsWith("Animal") }.map { it.inputStream().use(BookFiles::read) }
        assertEquals(animal[0].id, animal[1].id)
        File("build/reports/legacy-import-summary.txt").apply { parentFile!!.mkdirs(); writeText(report.joinToString("\n")) }
    }
}
