package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EbookCompatibilityTest {
    @Test fun epubUsesSpineOrderAndNavigationTitlesWithoutImportingTocAsProse() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, raw: String) { zip.putNextEntry(ZipEntry(path)); zip.write(raw.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""")
            entry("book.opf", """<package><manifest><item id="second" href="second.xhtml" media-type="application/xhtml+xml"/><item id="first" href="first.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="nav"/><itemref idref="first"/><itemref idref="second"/></spine></package>""")
            entry("toc.xhtml", """<html><body><nav epub:type="toc"><ol><li><a href="first.xhtml#start">Rain</a></li><li><a href="second.xhtml">Bird</a></li></ol></nav></body></html>""")
            entry("first.xhtml", "<html><body><p id='start'>First chapter.</p></body></html>")
            entry("second.xhtml", "<html><body><p>Second chapter.</p></body></html>")
        }
        val book = BookFiles.read(output.toByteArray().inputStream())
        assertEquals(listOf("Rain", "Bird"), book.chapters.map { it.title })
        assertEquals(listOf("First chapter.", "Second chapter."), book.chapters.flatMap { it.paragraphs }.map { it.text })
    }
    @Test fun readsRtfUnicodeCodePagesGroupsAndEscapedCharacters() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl{\f0 Hidden font;}}Chapter One\par A caf\'e9 \emdash open.\par \u38632?\u20572?\u20102?\u12290?\par Literal \{braces\} and \\slash.{\*\unknown Hidden text.}}"""
        val book = BookFiles.readDocument(rtf.byteInputStream(), "sample.rtf")
        assertEquals(listOf("Chapter One", "A café —open.", "雨停了。", "Literal {braces} and \\slash."), book.chapters.single().paragraphs.map { it.text })
        val gbk = """{\rtf1\ansi\ansicpg936 \'d3\'ea\'cd\'a3\'c1\'cb\'a1\'a3}"""
        assertEquals("雨停了。", BookFiles.readDocument(gbk.byteInputStream(), "zh.rtf").chapters.single().paragraphs.single().text)
    }

    @Test fun readsDocxHeadingsTablesTabsAndMetadata() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, raw: String) { zip.putNextEntry(ZipEntry(name)); zip.write(raw.toByteArray()); zip.closeEntry() }
            entry("docProps/core.xml", "<cp:coreProperties><dc:title>Word book</dc:title><dc:creator>Writer</dc:creator></cp:coreProperties>")
            entry("word/document.xml", """<w:document><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Chapter One</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">A bird </w:t></w:r><w:r><w:t>flew.</w:t><w:br/><w:t>Next line.</w:t><w:tab/><w:t>End.</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Table cell.</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""")
        }
        val book = BookFiles.read(output.toByteArray().inputStream(), "sample.docx")
        assertEquals("Word book", book.title)
        assertEquals("Writer", book.author)
        assertEquals(listOf("Chapter One", "A bird flew.\nNext line.\tEnd.", "Table cell."), book.chapters.single().paragraphs.map { it.text })
    }
    private fun epub(body: ByteArray, opf: ByteArray = """<package><metadata><title>Compatibility</title></metadata><manifest><item id="a" href="text/chapter%20one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""".toByteArray()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>""".toByteArray())
            entry("OPS/content.opf", opf)
            entry("OPS/text/chapter one.xhtml", body)
        }
        return output.toByteArray()
    }

    @Test fun epubAcceptsHtmlEntitiesBrokenTagsAndPreservesContainerText() {
        val body = """<html><body><h1>One</h1><div id="wrap">Before.<p>Rain &mdash; <em>again</em> &copy; 2026<p>Next.</p>After.</div><div>Last<br>line.</div><pre>Poem
  indented.</pre></body></html>"""
        val book = BookFiles.read(epub(body.toByteArray()).inputStream(), "book.epub")
        val paragraphs = book.chapters.single().paragraphs
        assertEquals(listOf("One", "Before.", "Rain — again © 2026", "Next.", "After.", "Last\nline.", "Poem\n  indented."), paragraphs.map { it.text })
        val styled = paragraphs[2]
        assertEquals("again", styled.text.substring(styled.marks.single().start, styled.marks.single().end))
    }

    @Test fun supportsGbkGb18030AndUtf16WithoutBom() {
        for (charset in listOf(Charset.forName("GBK"), Charset.forName("GB18030"), Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val text = "第一章 雨\n雨停了，窗外还有一只鸟。"
            val book = BookFiles.read(text.toByteArray(charset).inputStream(), "雨.txt")
            assertEquals("雨停了，窗外还有一只鸟。", book.chapters.single().paragraphs.last().text)
        }
        val supplementary = "第一章\n鸟飞过𠀀。"
        assertEquals(supplementary, DocumentImporter.decode(supplementary.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun respectsDeclaredChapterAndPackageEncoding() {
        val body = """<?xml version="1.0" encoding="windows-1252"?><html><body><p>A café — open.</p></body></html>"""
        val opf = """<?xml version="1.0" encoding="UTF-16"?><package><metadata><title>中文书名</title></metadata><manifest><item id="a" href="text/chapter%20one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>"""
        val book = BookFiles.read(epub(body.toByteArray(Charset.forName("windows-1252")), opf.toByteArray(Charsets.UTF_16)).inputStream())
        assertEquals("中文书名", book.title)
        assertEquals("A café — open.", book.chapters.single().paragraphs.single().text)
    }

    @Test fun nestedListsRetainLeadTextExactlyOnce() {
        val book = BookFiles.readDocument("<html><body><ul><li>Lead<p>Inner.</p>Tail</li></ul></body></html>".byteInputStream())
        assertEquals(listOf("Lead", "Inner.", "Tail"), book.chapters.single().paragraphs.map { it.text })
    }

    @Test fun readsFb2MetadataChaptersVerseAndNotesWithoutEmbeddedBinary() {
        val raw = """<?xml version="1.0"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>A bird</book-title><author><first-name>A</first-name><last-name>Writer</last-name></author></title-info></description><body><section><title><p>Chapter One</p></title><p>A <emphasis>bird</emphasis> flew.</p><section><title><p>Chapter Two</p></title><poem><stanza><v>Line one.</v><v>Line two.</v></stanza></poem></section></section></body><body name="notes"><section><p>A note.</p></section></body><binary>NOT_BODY</binary></FictionBook>"""
        val book = BookFiles.read(raw.byteInputStream(), "bird.fb2")
        assertEquals("A bird", book.title)
        assertEquals("A Writer", book.author)
        assertEquals(listOf("Chapter One", "Chapter Two", "注释"), book.chapters.map { it.title })
        assertEquals(listOf("Chapter One", "A bird flew.", "Chapter Two", "Line one.", "Line two.", "A note."), book.chapters.flatMap { it.paragraphs }.map { it.text })
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip -> zip.putNextEntry(ZipEntry("bird.fb2")); zip.write(raw.toByteArray()); zip.closeEntry() }
        assertEquals(book.id, BookFiles.read(zipped.toByteArray().inputStream(), "bird.fb2.zip").id)
    }
}
