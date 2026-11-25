package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArticlesTest {
    @Test fun pastedTextUsesShortFirstLineAsTitleAndJoinsWrappedLines() {
        val book = Articles.fromText("""
            Can Britannia rule the waves again?

            The Royal Navy is betting on drones. Its admirals
            hope that uncrewed vessels will fill the gaps.

            Critics are less sure.
        """.trimIndent())
        assertTrue(book.isArticle)
        assertEquals("Can Britannia rule the waves again?", book.title)
        val body = book.chapters.single().paragraphs.filterNot { it.heading }.map { it.text }
        assertEquals(listOf("The Royal Navy is betting on drones. Its admirals hope that uncrewed vessels will fill the gaps.", "Critics are less sure."), body)
    }

    @Test fun pastedTextWithoutBlankLinesKeepsSentencesTogetherAndRepairsHyphens() {
        val book = Articles.fromText("Inflation is falling across the rich world, and central\nbankers are relieved. Yet the eco-\nnomy remains fragile.\nMarkets disagree.")
        val body = book.chapters.single().paragraphs.filterNot { it.heading }.map { it.text }
        assertEquals(listOf("Inflation is falling across the rich world, and central bankers are relieved. Yet the economy remains fragile.", "Markets disagree."), body)
        assertEquals("Inflation is falling across the rich world, and…", book.title)
    }

    @Test fun samePasteGivesSameArticle() {
        assertEquals(Articles.fromText("A title\n\nSome body text here.").id, Articles.fromText("A title\n\nSome body text here.", now = 5).id)
    }

    @Test fun linkDetection() {
        assertTrue(Articles.isLinkOnly("Why cities matter https://example.com/a/b?x=1"))
        assertEquals("https://example.com/a", Articles.firstUrl("see https://example.com/a."))
        assertFalse(Articles.isLinkOnly("plain text without any link"))
    }

    @Test fun webPageKeepsArticleParagraphsOnly() {
        val sentence = "This is a sentence of a real article body with enough words in it."
        val html = """<html><head><meta property="og:title" content="Real headline"><title>Site</title></head><body>
            <nav><p>Home News Sport and more links for navigating around here</p></nav>
            <article>${(1..12).joinToString("") { "<p>$sentence $it</p>" }}<figure><figcaption>A photo caption with several words in it</figcaption></figure></article>
            <footer><p>Copyright and other footer text that is long enough to count</p></footer></body></html>"""
        val book = Articles.fromHtml(html, "https://www.example.com/story")
        assertEquals("Real headline", book.title)
        assertEquals("example.com", book.author)
        assertEquals(12, book.chapters.single().paragraphs.count { !it.heading })
        assertTrue(book.chapters.single().paragraphs.none { it.text.contains("Copyright") || it.text.contains("caption") })
    }

    @Test fun passageQuestionsSendOnlyNearbyParagraphsUnlessAsked() {
        val book = Articles.fromText("Title here\n\nFirst paragraph says one thing.\n\nSecond paragraph adds more.\n\nThe LAST paragraph concludes.")
        val passage = ReadingQuestions.selection(book, listOf(PassageRange(0, 2, 0, 6)))
        assertFalse(passage.sendsArticle)
        val body = ReadingQuestions.body("m", passage, emptyList(), "Why?")
        assertTrue(body.contains("所在段落") && body.contains("First paragraph") && body.contains("Second paragraph"))
        assertFalse(body.contains("The LAST paragraph"))
        assertFalse(body.contains("「生词」"))
        assertTrue(ReadingQuestions.body("m", passage.copy(withVocab = true), emptyList(), "Why?").contains("「生词」"))
        val full = ReadingQuestions.body("m", passage.copy(useArticle = true), emptyList(), "Why?")
        assertTrue(full.contains("全文") && full.contains("The LAST paragraph"))
        val whole = ReadingQuestions.wholeArticle(book)
        assertTrue(whole.wholeArticle && whole.sendsArticle)
        val messages = JSONObject(ReadingQuestions.body("m", whole, emptyList(), "全文大意？")).getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("考研"))
        assertFalse(messages.getJSONObject(1).getString("content").contains("重点选文"))
    }

    @Test fun chatThreadsRoundTripAndCacheRepeatedQuestions() {
        val t = ChatThread(ChatThread.idFor(listOf(PassageRange(0, 1, 0, 5))), "a".repeat(24), listOf(PassageRange(0, 1, 0, 5)), "First", "Title · 第 1 段",
            false, listOf(ReadingExchange("翻译这段", "这是译文。")), 1)
        val back = ChatStore.decode(t.bookId, ChatStore.encode(listOf(t))).single()
        assertEquals(t, back)
        assertEquals(0, back.cached(" 翻译这段 "))
        assertEquals(-1, back.cached("另一个问题"))
        assertEquals(ChatThread.ARTICLE, ChatThread.idFor(emptyList()))
        assertEquals(listOf(t to 0), ChatStore.search(listOf(t), "译文"))
        assertEquals("这是译文。", ChatStore.snippet("这是译文。", "译文"))
    }

    @Test fun economistIssueSplitsIntoArticlesBySection() {
        val articles = EconomistIssue.read(sampleIssue(), now = 100)
        assertNotNull(articles)
        assertEquals(listOf("America’s neglect of global science is backfiring"), articles!!.map { it.title })
        val a = articles.single()
        assertEquals("Leaders", a.section)
        assertEquals("The Economist · 2026-11-21", a.collection)
        assertEquals("The Trump administration’s reforms have overcorrected", a.subtitle)
        assertEquals("The Economist · Nov 19th 2026", a.author)
        val body = a.chapters.single().paragraphs.filterNot { it.heading }.map { it.text }
        assertTrue(body.first().startsWith("AMERICAN SCIENCE leads"))
        assertTrue(body.none { it.contains("■") || it.contains("Co-operate to accumulate") })
        assertTrue(Articles.examTopic(a.section))
    }

    @Test fun ordinaryEpubIsNotAnIssue() {
        assertNull(EconomistIssue.read(epub(mapOf("OEBPS/content.opf" to opf("A Novel"), "OEBPS/toc.ncx" to "<ncx/>", "OEBPS/c1.html" to "<p>Hello</p>"))))
    }

    /** Runs against a real downloaded issue when ECONOMIST_EPUB points at one. */
    @Test fun realIssueWhenAvailable() {
        val path = System.getenv("ECONOMIST_EPUB")
        assumeTrue(path != null && File(path).exists())
        val articles = EconomistIssue.read(File(path!!).readBytes())!!
        println("articles=${articles.size} sections=${articles.map { it.section }.distinct()}")
        articles.take(3).forEach { println("${it.section} | ${it.title} | ${it.wordCount} words | ${it.chapters.single().paragraphs[1].text.take(80)}") }
        assertTrue(articles.size > 40)
        val imported = EconomistIssue.readWithImages(File(path).readBytes())!!
        // Short items are only kept when they lead with a picture, like the weekly cartoon.
        assertTrue(articles.filter { it.wordCount < 120 }.all { it.id in imported.images })
        val cartoon = articles.first { it.title.startsWith("Cartoon") }
        val links = cartoon.chapters.single().paragraphs.filterNot { it.heading }.map { it.text }
        assertTrue(links.toString(), "The incredible shrinking legacy of Donald J. Trump" in links && "Iran is functioning without a supreme leader" in links)
        assertEquals(articles.size, articles.map { it.id }.distinct().size)
        val images = EconomistIssue.readWithImages(File(path).readBytes())!!.images
        println("images=${images.size} first=${images.values.first().size} bytes")
        assertTrue(images.size > articles.size / 2)
    }

    private fun sampleIssue(): ByteArray {
        val title = """<html><body><p class="calibre1"><span class="te_section_title">Leaders</span><span> | Co-operate to accumulate</span></p></body></html>"""
        val words = (1..45).joinToString(" ") { "word$it" }
        val body = """<html><body><h1 class="te_article_title">America’s neglect of global science is backfiring</h1>
            <h3 class="te_article_rubric">The Trump administration’s reforms have overcorrected</h3>
            <p class="calibre1"><img src="../images/1.jpeg" class="te_img"/></p><hr/>
            <span class="te_article_datepublished">Nov 19th 2026</span><p class="calibre3"> </p>
            <p class="calibre1">AMERICAN SCIENCE leads the world, as we <a href="https://x">exclusively report</a>. $words</p>
            <p class="calibre1">Second paragraph. $words</p>
            <p class="calibre1">Third paragraph. $words <span class="te_section_title">■</span></p></body></html>"""
        val cartoon = """<html><body><h1 class="te_article_title">Cartoon</h1><p>Dig deeper.</p></body></html>"""
        val ncx = """<?xml version="1.0"?><ncx><navMap>
            <navPoint><navLabel><text>The world this week</text></navLabel><content src="text/part0000.html"/>
              <navPoint><navLabel><text>Cartoon</text></navLabel><content src="text/part0004_split_000.html"/></navPoint></navPoint>
            <navPoint><navLabel><text>Leaders</text></navLabel><content src="text/part0000.html"/>
              <navPoint><navLabel><text>America’s neglect of global science is backfiring</text></navLabel><content src="text/part0011_split_000.html"/></navPoint></navPoint>
            </navMap></ncx>"""
        val manifest = """<item id="a" href="text/part0004_split_000.html" media-type="application/xhtml+xml"/>
            <item id="b" href="text/part0011_split_000.html" media-type="application/xhtml+xml"/>
            <item id="c" href="text/part0011_split_001.html" media-type="application/xhtml+xml"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        return epub(mapOf("content.opf" to opf("TheEconomist.2026.11.21", manifest, "<itemref idref=\"a\"/><itemref idref=\"b\"/><itemref idref=\"c\"/>"),
            "toc.ncx" to ncx, "text/part0004_split_000.html" to cartoon, "text/part0011_split_000.html" to title, "text/part0011_split_001.html" to body),
            opfPath = "content.opf")
    }

    private fun opf(title: String, manifest: String = "", spine: String = "") =
        """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata>
        <manifest>$manifest</manifest><spine>$spine</spine></package>"""

    private fun epub(files: Map<String, String>, opfPath: String = "OEBPS/content.opf"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray())
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?><container><rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()) }
        }
        return out.toByteArray()
    }
}

class ImportExtrasTest {
    @Test fun markdownArticlesLoseTheirMarkers() {
        val text = Articles.stripMarkdown("# Big title\n\nSome **bold** and *italic* with a [link](https://x.y) and `code`.\n\n![pic](a.png)\n---\n> quoted line")
        assertEquals("Big title\n\nSome bold and italic with a link and code.\n\n\n\nquoted line", text)
        val book = Articles.fromText(text)
        assertEquals("Big title", book.title)
    }

    @Test fun librarySearchRanksTitleMatchesFirst() {
        val a = Articles.fromText("Cheap nails changed the world\n\nNothing about it here.")
        val b = Articles.fromText("Another story\n\nNails, nails and more nails in the text.")
        val hits = com.jknl.englishreader.ui.LibrarySearch.rank(listOfNotNull(
            com.jknl.englishreader.ui.LibrarySearch.article(ShelfBook.from(b), b, "nails"),
            com.jknl.englishreader.ui.LibrarySearch.article(ShelfBook.from(a), a, "nails")))
        assertEquals(listOf(a.id, b.id), hits.map { it.article.id })
        assertEquals(3, hits[1].count)
        assertNull(com.jknl.englishreader.ui.LibrarySearch.article(ShelfBook.from(a), a, "zebra"))
    }
}
