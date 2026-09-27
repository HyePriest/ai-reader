package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DirectImportTest {
    private fun epub(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(path: String, value: String) { zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""")
            entry("book.opf", """<package><metadata><title>Sample</title></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""")
            entry("chapter.xhtml", "<html><body>$body</body></html>")
        }
        return out.toByteArray()
    }

    @Test fun readsUnmarkedBilingualEpubAndSeparateChineseEpub() {
        val b = BookFiles.read(epub("<h1>Rain</h1><p>It rained.</p><p>下雨了。</p><p>We waited.</p><p>我们等着。</p>").inputStream())
        assertEquals(2, b.chapters.single().alignments.size)
        assertEquals(3, b.chapters.single().paragraphs.size)
        val chinese = ChineseImport.read(epub("<h1>雨</h1><p>下雨了。</p><p>我们等着。</p>").inputStream(), "中文.epub")
        assertEquals(listOf("下雨了。", "我们等着。"), chinese.chapters.single().paragraphs)
    }
    private fun book(text: String = "The rain stopped. She went home.") = Book("0123456789abcdef01234567", "Sample", "Author", listOf(
        Chapter("one", "Chapter One", listOf(Paragraph("p1", text)), emptyList()),
        Chapter("two", "Chapter Two", listOf(Paragraph("p1", "Another chapter.")), emptyList())
    ), 1L)

    private fun document() = ChineseDocument("zh", "中文", listOf(ChineseChapter("一", "第一章", listOf("雨停了。她回家了。")), ChineseChapter("二", "第二章", listOf("另一章。"))))

    private fun exactPlan(b: Book): ChapterPairing {
        val en = ChineseImport.englishUnits(b.chapters[0], true)
        val zh = ChineseImport.chineseUnits(document().chapters[0], true)
        return ChapterPairing(0, 0, en, zh, listOf(PairDraft(0, 1, 0, 1), PairDraft(1, 2, 1, 2)), false)
    }

    @Test fun readsPlainEnglishAndAlternatingTextInOneFile() {
        val english = BookFiles.read("Chapter One\nThe rain stopped.\nShe went home.".byteInputStream(), "Story.txt")
        assertEquals("Story", english.title)
        assertEquals("Chapter One", english.chapters.single().title)
        assertTrue(english.chapters.single().alignments.isEmpty())
        val bilingual = BookFiles.read("Chapter One\nThe rain stopped.\n雨停了。\nShe went home.\n她回家了。".byteInputStream())
        val c = bilingual.chapters.single()
        assertEquals(3, c.paragraphs.size)
        assertEquals(2, c.alignments.size)
        assertEquals("雨停了。", c.translationAt(c.paragraphs[1].id, 2)?.chinese)
        assertFalse(c.paragraphs.any { DocumentImporter.chinese(it.text) })
    }

    @Test fun genericHtmlTablesAndScriptsAreHandledWithoutExecution() {
        val raw = """<html><title>Table</title><script>throw 'bad'</script><body><h1>One</h1><table><tr><td>It rained.</td><td>下雨了。</td></tr><tr><td>We waited.</td><td>我们等着。</td></tr></table></body></html>"""
        val b = BookFiles.read(raw.byteInputStream())
        assertEquals(2, b.chapters.single().alignments.size)
        assertTrue(b.chapters.single().paragraphs.none { it.text.contains("throw") })
    }

    @Test fun rawChineseCanBeReadAsStandaloneBookOrCompanion() {
        val raw = "第一章 雨\n雨停了。她回家了。\n第二章 晚上\n夜色降临。"
        val doc = ChineseImport.read(raw.byteInputStream(), "中文.txt")
        assertEquals(2, doc.chapters.size)
        assertEquals("第一章 雨", doc.chapters[0].title)
        assertTrue(BookFiles.read(raw.byteInputStream()).isChinese)
    }

    @Test fun supportsChineseBeforeEnglishButRejectsUnpairedTrailingChinese() {
        val b = BookFiles.read("你好。\nHello.\n再见。\nGoodbye.".byteInputStream())
        assertEquals(2, b.chapters.single().alignments.size)
        assertEquals("你好。", b.chapters.single().translationAt("p1", 1)?.chinese)
        assertThrows(IllegalArgumentException::class.java) { BookFiles.read("你好。\nHello.\n没有对应的英文。".byteInputStream()) }
    }

    @Test fun supportsUtf16AndRejectsCorruptOrBinaryText() {
        assertEquals("Hello.", DocumentImporter.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "Hello.".toByteArray(Charsets.UTF_16LE)))
        assertThrows(IllegalStateException::class.java) { DocumentImporter.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { DocumentImporter.decode("ab\u0000c".toByteArray()) }
    }

    @Test fun chineseSplittingIsLosslessIncludingWhitespaceAndQuotes() {
        val raw = " “雨停了！”\n她回家了。  "
        val units = ChineseImport.chineseUnits(ChineseChapter("c", "", listOf(raw)), true)
        assertEquals(raw, units.joinToString("") { it.text })
        assertEquals(2, units.size)
        val long = "他打开门，屋里很黑；他摸索开关。"
        val clauses = ChineseImport.chineseUnits(ChineseChapter("c", "", listOf(long)), true, true)
        assertEquals(3, clauses.size)
        assertEquals(long, clauses.joinToString("") { it.text })
        val paragraphs = ChineseImport.chineseUnits(ChineseChapter("c", "", listOf("第一段。", "第二段。")), true)
        assertEquals("第一段。\n\n第二段。", paragraphs.joinToString("") { it.text })
    }

    @Test fun proposalsAccountForEveryUnitWithoutDuplicateRanges() {
        val plan = ChineseImport.propose(book(), document(), 0, 0)
        assertEquals(plan.english.indices.toList(), plan.pairs.flatMap { (it.enStart until it.enEnd).toList() })
        assertEquals(plan.chinese.indices.toList(), plan.pairs.flatMap { (it.zhStart until it.zhEnd).toList() })
        plan.pairs.filter { it.enStart == it.enEnd || it.zhStart == it.zhEnd }.forEach { assertFalse(it.selected) }
    }

    @Test fun importingOneChapterPreservesEnglishIdsAndOtherChapterWithSameParagraphId() {
        val before = book()
        val result = ChineseImport.prepare(before, listOf(exactPlan(before)), false).book
        assertEquals(before.id, result.id)
        assertEquals(before.chapters[1], result.chapters[1])
        assertEquals(before.chapters[0].paragraphs[0].text, result.chapters[0].paragraphs[0].text)
        assertEquals(before.chapters[0].paragraphs[0].id, result.chapters[0].paragraphs[0].id)
        assertEquals("雨停了。", result.chapters[0].translationAt("p1", 1)?.chinese)
        assertEquals("她回家了。", result.chapters[0].translationAt("p1", 22)?.chinese)
        assertEquals(2, result.chapters[0].paragraphs[0].units.size)
        assertTrue(before.chapters[0].alignments.isEmpty())
    }

    @Test fun overlapIsPreservedByDefaultAndCannotEraseUnselectedRemainder() {
        val original = book()
        val paragraph = original.chapters[0].paragraphs[0]
        val old = Alignment("old", listOf(SourceRange("p1", 0, paragraph.text.length)), "原来的完整译文", "original")
        val before = original.copy(chapters = listOf(original.chapters[0].copy(alignments = listOf(old)), original.chapters[1]))
        val plan = exactPlan(before)
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(before, listOf(plan), false) }
        val partial = plan.copy(pairs = listOf(plan.pairs[0]))
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(before, listOf(partial), true) }
        val full = ChineseImport.prepare(before, listOf(plan), true)
        assertEquals(1, full.conflicts)
        assertEquals(2, full.book.chapters[0].alignments.size)
        assertEquals(old, before.chapters[0].alignments.single())
    }

    @Test fun duplicateEnglishOrChineseRangesAndChapterReuseAreRejected() {
        val b = book(); val plan = exactPlan(b)
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(b, listOf(plan.copy(pairs = listOf(plan.pairs[0], plan.pairs[0]))), false) }
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(b, listOf(plan, plan), false) }
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(b, listOf(plan, plan.copy(chapterIndex = 1)), false) }
    }

    @Test fun staleTextAndInvalidRangeCannotBeSaved() {
        val b = book(); val plan = exactPlan(b)
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(book("The snow stopped. She went home."), listOf(plan), false) }
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(b, listOf(plan.copy(pairs = listOf(PairDraft(0, 100, 0, 1)))), false) }
    }

    @Test fun oneChineseChapterCanSupplyDisjointRangesToTwoEnglishChapters() {
        val b = book().copy(chapters = listOf(
            Chapter("one", "One", listOf(Paragraph("p1", "Rain stopped.")), emptyList()),
            Chapter("two", "Two", listOf(Paragraph("p1", "She left.")), emptyList())))
        val zh = listOf(ImportUnit("雨停了。"), ImportUnit("她走了。"))
        val first = ChapterPairing(0, 0, ChineseImport.englishUnits(b.chapters[0], true), zh, listOf(PairDraft(0, 1, 0, 1)), false)
        val second = ChapterPairing(1, 0, ChineseImport.englishUnits(b.chapters[1], true), zh, listOf(PairDraft(0, 1, 1, 2)), false)
        val saved = ChineseImport.prepare(b, listOf(first, second), false).book
        assertEquals("雨停了。", saved.chapters[0].alignments.single().chinese)
        assertEquals("她走了。", saved.chapters[1].alignments.single().chinese)
        assertThrows(IllegalArgumentException::class.java) { ChineseImport.prepare(b, listOf(first, second.copy(pairs = listOf(PairDraft(0, 1, 0, 1)))), false) }
    }

    @Test fun utf16RangesAndManyToOneArePreserved() {
        val b = book("A bird 🦜 flew. She smiled.")
        val en = ChineseImport.englishUnits(b.chapters[0], true)
        en.forEach { u -> val r = u.range!!; assertEquals(u.text, b.chapters[0].paragraphs[0].text.substring(r.start, r.end)) }
        val plan = ChapterPairing(0, 0, en, listOf(ImportUnit("一只鸟飞过，她笑了。")), listOf(PairDraft(0, en.size, 0, 1)), false)
        val c = ChineseImport.prepare(b, listOf(plan), false).book.chapters[0]
        assertEquals("一只鸟飞过，她笑了。", c.translationAt("p1", 20)?.chinese)
        assertEquals(en.size, c.alignments.single().ranges.size)
    }

    @Test fun chapterHeadingsAndTitleOnlyFrontMatterArePreserved() {
        val raw = Book("0123456789abcdef01234567", "Title", "", listOf(Chapter("cover", "Title", listOf(Paragraph("p", "Title", true)), emptyList()), book().chapters[0]), 0)
        assertEquals(raw, DocumentImporter.bilingual(raw))
        val bilingual = BookFiles.read("<h1>Rain</h1><h2>雨</h2><p>It rained.</p><p>下雨了。</p>".byteInputStream())
        assertEquals(2, bilingual.chapters.single().alignments.size)
    }
}

