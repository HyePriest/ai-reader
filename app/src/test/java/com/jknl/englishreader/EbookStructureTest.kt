package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EbookStructureTest {
    @Test fun reconstructedKindleKeepsNcxAndCrossFileNotes() {
        val book = KindleBookReader.read("0123456789abcdef01234567", "Kindle", "", linkedMapOf(
            "part00000.html" to """<h1 id="intro">前言</h1><p>Intro.</p><h1 id="chapter">Chapter One</h1><p>Read<sup><a href="part00001.html#fn1">1</a></sup> more.</p>""",
            "part00001.html" to """<h1>注释</h1><aside id="fn1" role="doc-footnote"><p>Kindle note.</p></aside>"""),
            """<ncx><navMap><navPoint><navLabel><text>前言</text></navLabel><content src="part00000.html#intro"/></navPoint><navPoint><navLabel><text>Chapter One</text></navLabel><content src="part00000.html#chapter"/></navPoint></navMap></ncx>""")
        assertEquals(listOf("前言", "Chapter One", "注释"), book.contents.map { it.title })
        val p = book.chapters[1].paragraphs.last()
        assertEquals("Kindle note.", p.annotations.single().text)
        assertEquals(1, book.contents[1].chapter)
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { z -> files.forEach { (path, bytes) -> z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() } }
        return output.toByteArray()
    }
    private fun epub(opf: String, files: Map<String, String>, cover: ByteArray? = null): ByteArray {
        val entries = linkedMapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""".toByteArray(),
            "OPS/book.opf" to opf.toByteArray())
        files.forEach { (path, text) -> entries["OPS/$path"] = text.toByteArray() }
        cover?.let { entries["OPS/cover.png"] = it }
        return zip(entries)
    }

    @Test fun epub3KeepsNestedTocAnchorsFrontMatterEndnotesAndCover() {
        val opf = """<package><metadata><title>完整的书</title></metadata><manifest>
            <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
            <item id="intro" href="intro.xhtml" media-type="application/xhtml+xml"/>
            <item id="main" href="text/main.xhtml" media-type="application/xhtml+xml"/>
            <item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="cover.png" properties="cover-image" media-type="image/png"/>
            </manifest><spine><itemref idref="nav"/><itemref idref="intro"/><itemref idref="main"/><itemref idref="notes" linear="no"/></spine></package>"""
        val cover = byteArrayOf(1, 2, 3)
        var found: ByteArray? = null
        var coverBook = ""
        val source = epub(opf, mapOf(
            "nav.xhtml" to """<nav epub:type="toc"><ol><li><a href="intro.xhtml">前言</a></li><li><a href="text/main.xhtml#part">第一章</a><ol><li><a href="text/main.xhtml#second">第二节</a></li></ol></li><li><a href="notes.xhtml">文后注释</a></li></ol></nav>""",
            "intro.xhtml" to "<p>前言文字保留。</p>",
            "text/main.xhtml" to """<section id="part"><h1>原文标题</h1><p>文字<a epub:type="noteref" href="../notes.xhtml#n1">①</a>继续。</p><h2 id="second">第二节</h2><p>第二节正文。</p><p><a href="#second">普通章节链接</a></p></section>""",
            "notes.xhtml" to """<aside epub:type="footnote" id="n1"><p>第一段注释。</p><p>第二段注释。<a epub:type="backlink" href="text/main.xhtml#ref">↩</a></p></aside>"""), cover)
        val book = BookFiles.read(source.inputStream(), "test.epub") { id, data -> coverBook = id; found = data }
        assertArrayEquals(cover, found); assertEquals(book.id, coverBook)
        assertEquals(listOf("前言", "第一章", "文后注释"), book.chapters.map { it.title })
        assertEquals(listOf("前言", "第一章", "第二节", "文后注释"), book.contents.map { it.title })
        assertEquals(listOf(0, 0, 1, 0), book.contents.map { it.depth })
        val subsection = book.contents[2]
        assertEquals("第二节", book.chapters[subsection.chapter].paragraphs[subsection.paragraph].text)
        val p = book.chapters[1].paragraphs.first { it.annotations.isNotEmpty() }
        val note = p.annotations.single()
        assertEquals("①", p.text.substring(note.start, note.end))
        assertTrue(note.text.contains("第一段注释。")); assertTrue(note.text.contains("第二段注释。")); assertFalse(note.text.contains("↩"))
        assertTrue(book.chapters[2].paragraphs.any { it.text.contains("第一段注释。") })
        assertTrue(book.chapters[1].paragraphs.last().annotations.isEmpty())
    }

    @Test fun epub2ReadsNcxFragmentsNamedAnchorsAndLegacyCover() {
        val opf = """<package><metadata><title>Legacy</title><meta name="cover" content="image"/></metadata><manifest>
            <item id="main" href="main.xhtml" media-type="application/xhtml+xml"/>
            <item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="image" href="cover.png" media-type="image/png"/>
            </manifest><spine toc="toc"><itemref idref="main"/></spine></package>"""
        val cover = byteArrayOf(4, 5)
        var found: ByteArray? = null
        val book = EpubImporter().read(epub(opf, mapOf(
            "toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>序言</text></navLabel><content src="main.xhtml#intro"/></navPoint><navPoint><navLabel><text>Chapter One</text></navLabel><content src="main.xhtml#chapter"/></navPoint></navMap></ncx>""",
            "main.xhtml" to """<p><a name="intro"></a>Intro.</p><h1 id="chapter">Chapter One</h1><p>Read<sup><a href="#fn1">1</a></sup> more.</p><p><a name="fn1"></a>1 A legacy note.</p>"""), cover).inputStream()) { _, data -> found = data }
        assertArrayEquals(cover, found)
        assertEquals(listOf("序言", "Chapter One"), book.contents.map { it.title })
        assertEquals(1, book.contents[1].paragraph)
        val note = book.chapters.single().paragraphs[2].annotations.single()
        assertEquals("1", book.chapters.single().paragraphs[2].text.substring(note.start, note.end))
        assertTrue(note.text.contains("A legacy note."))
    }

    @Test fun fb2KeepsCoverAndLinkedNoteInPopupModelAndAppendix() {
        val raw = """<FictionBook><description><title-info><book-title>FB2</book-title><coverpage><image l:href="#cover"/></coverpage></title-info></description>
            <body><section><title><p>前言</p></title><p>文字<a type="note" l:href="#n1">[1]</a>继续。</p></section></body>
            <body name="notes"><section id="n1"><p>FB2 注释内容。</p></section></body><binary id="cover">AQID</binary></FictionBook>"""
        var cover: ByteArray? = null
        val book = BookFiles.read(raw.byteInputStream(), "test.fb2") { _, data -> cover = data }
        assertArrayEquals(byteArrayOf(1, 2, 3), cover)
        assertEquals("注释", book.chapters.last().title)
        val p = book.chapters.first().paragraphs.last()
        assertEquals("[1]", p.text.substring(p.annotations.single().start, p.annotations.single().end))
        assertEquals("FB2 注释内容。", p.annotations.single().text)
        assertTrue(book.chapters.last().paragraphs.any { it.text == "FB2 注释内容。" })
    }

    @Test fun docxLinksFootnotesAndEndnotesAndRetainsAllNoteBodies() {
        val data = zip(mapOf(
            "word/document.xml" to """<w:document><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>前言</w:t></w:r></w:p><w:p><w:r><w:t>正文</w:t><w:footnoteReference w:id="1"/><w:t>继续</w:t><w:endnoteReference w:id="2"/></w:r></w:p></w:body></w:document>""".toByteArray(),
            "word/footnotes.xml" to """<w:footnotes><w:footnote w:id="-1" w:type="separator"/><w:footnote w:id="1"><w:p><w:r><w:t>脚注第一段</w:t></w:r></w:p><w:p><w:r><w:t>脚注第二段</w:t></w:r></w:p></w:footnote></w:footnotes>""".toByteArray(),
            "word/endnotes.xml" to """<w:endnotes><w:endnote w:id="2"><w:p><w:r><w:t>尾注内容</w:t></w:r></w:p></w:endnote></w:endnotes>""".toByteArray()))
        val book = BookFiles.read(data.inputStream(), "book.docx")
        assertEquals(listOf("前言", "注释"), book.chapters.map { it.title })
        val p = book.chapters.first().paragraphs.last()
        assertEquals(listOf("〔1〕", "〔2〕"), p.annotations.map { p.text.substring(it.start, it.end) })
        assertEquals("脚注第一段\n\n脚注第二段", p.annotations[0].text)
        assertEquals("尾注内容", p.annotations[1].text)
        assertEquals(2, book.chapters.last().paragraphs.size)
    }

    @Test fun htmlNotesAreLocalAndOrdinaryLinksAreNotAnnotations() {
        val book = BookFiles.readDocument("""<h1>正文</h1><p>文字<a role="doc-noteref" href="#note">[1]</a><a href="https://example.com/#note">外部</a><a href="#section">章节</a></p><h2 id="section">第二节</h2><aside role="doc-footnote" id="note"><p>说明</p></aside>""".byteInputStream())
        val p = book.chapters.first().paragraphs.last()
        assertEquals(1, p.annotations.size)
        assertEquals("说明", p.annotations.single().text)
    }
}
