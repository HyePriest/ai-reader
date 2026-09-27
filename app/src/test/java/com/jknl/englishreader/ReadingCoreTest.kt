package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadingCoreTest {
    private fun epub(body: String, extra: Pair<String, String>? = null): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>""")
            entry("OPS/content.opf", """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""")
            entry("OPS/a.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""")
            extra?.let { entry(it.first, it.second) }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test fun bilingualGroupsPreserveInlineTextAndDoNotDisplayChineseAsEnglish() {
        val c = EpubImporter().read(epub("""<p><span data-er-id="a">The <em>rain</em> came.</span> <span data-er-id="b">Nora waited.</span></p><p data-er-for="a b">雨来了。诺拉等着。</p>""")).chapters.single()
        assertEquals("The rain came. Nora waited.", c.paragraphs.single().text)
        assertEquals("rain", c.paragraphs.single().let { it.text.substring(it.marks.single().start, it.marks.single().end) })
        assertEquals(2, c.alignments.single().ranges.size)
        assertEquals("雨来了。诺拉等着。", c.translationAt("p0", 19)?.chinese)
        assertEquals("已配中文", c.coverage())
    }

    @Test fun originalSampleHasFullPartialAndMissingChinese() {
        val file = listOf(File("src/main/assets/a-window-in-the-rain.epub"), File("app/src/main/assets/a-window-in-the-rain.epub")).first { it.exists() }
        val book = file.inputStream().use { EpubImporter().read(it) }
        assertEquals(listOf("已配中文", "部分配有中文", "暂无中文"), book.chapters.map { it.coverage() })
        assertTrue(book.chapters.first().alignments.any { it.ranges.size > 1 })
    }

    @Test fun missingTargetFailsInsteadOfSilentlyMisaligning() {
        assertThrows(IllegalArgumentException::class.java) { EpubImporter().read(epub("""<p data-er-id="a">Hello.</p><p data-er-for="unknown">你好。</p>""")) }
    }

    @Test fun overlappingMappingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EpubImporter().read(epub("""<p data-er-id="a">Hello.</p><p data-er-for="a">你好。</p><p data-er-for="a">您好。</p>""")) }
    }

    @Test fun pathTraversalAndEntityDeclarationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EpubImporter().read(epub("<p>Hello.</p>", "../outside" to "bad")) }
        assertThrows(IllegalArgumentException::class.java) { EpubImporter().read(epub("<!ENTITY x SYSTEM 'file:///private'><p>&x;</p>")) }
    }

    @Test fun ordinaryEnglishHasNoInventedChinese() {
        val c = EpubImporter().read(epub("<h1>A chapter</h1><p>Read this.</p>")).chapters.single()
        assertEquals("暂无中文", c.coverage())
        assertNull(c.translationAt("p1", 3))
    }

    @Test fun replacingOneGroupPreservesOtherTranslationsAndOriginalBook() {
        val c = Chapter("c", "C", listOf(Paragraph("a", "One. Two. Three.")), listOf(
            Alignment("one", listOf(SourceRange("a", 0, 4), SourceRange("a", 5, 9)), "一二", "test"),
            Alignment("three", listOf(SourceRange("a", 10, 16)), "三", "test")))
        val updated = Alignments.replacing(c, Alignment("new", listOf(SourceRange("a", 5, 9)), "二", "manual"))
        assertNull(updated.translationAt("a", 1))
        assertEquals("二", updated.translationAt("a", 6)?.chinese)
        assertEquals("三", updated.translationAt("a", 11)?.chinese)
        assertEquals("一二", c.translationAt("a", 1)?.chinese)
    }

    @Test fun wordAndSentenceSelectionHandleApostrophesHyphensAndHonorifics() {
        val text = "Mr. Green said, ‘Don't wait.’ She picked up the well-worn book."
        val dont = TextLocator.wordAt(text, text.indexOf("Don't") + 2)!!
        assertEquals("Don't", text.substring(dont))
        val hyphen = TextLocator.wordAt(text, text.indexOf("well-worn") + 5)!!
        assertEquals("well-worn", text.substring(hyphen))
        val sentence = TextLocator.sentenceAt(text, text.indexOf("Green"))
        assertTrue(text.substring(sentence).startsWith("Mr. Green"))
        assertNull(TextLocator.wordAt(text, text.indexOf(' ')))
    }
}
