package com.jknl.englishreader.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Parse reconstructed Kindle files with their filenames intact, including cross-file references. */
internal object KindleBookReader {
    fun read(id: String, title: String, author: String, markup: Map<String, String>, ncx: String): Book {
        val documents = markup.mapValues { Jsoup.parse(it.value) }
        val references = HtmlReferences(documents)
        val chapters = mutableListOf<Chapter>()
        val destinations = mutableMapOf<HtmlReferences.Target, ReadingPosition>()
        documents.forEach { (path, doc) ->
            var anchors = emptyMap<String, SourceRange>()
            val extracted = HtmlChapterReader.read(doc.body(), path, "正文", noteText = { references.note(path, it) }, onAnchors = { anchors = it })
            if (extracted.paragraphs.none { !it.heading }) return@forEach
            val groups = DocumentImporter.build(title, extracted.paragraphs).chapters
            val first = chapters.size
            groups.forEachIndexed { index, chapter -> chapters += chapter.copy(id = "$path:$index") }
            destinations[HtmlReferences.Target(path, "")] = ReadingPosition(first)
            anchors.forEach anchor@{ (fragment, source) ->
                val ci = (first until chapters.size).firstOrNull { chapters[it].paragraphs.any { p -> p.id == source.paragraph } } ?: return@anchor
                val pi = chapters[ci].paragraphs.indexOfFirst { it.id == source.paragraph }
                destinations[HtmlReferences.Target(path, fragment)] = ReadingPosition(ci, pi, source.start)
            }
        }
        require(chapters.any { c -> c.paragraphs.any { !it.heading } }) { "没有找到 Kindle 文字正文" }
        val toc = Jsoup.parse(ncx, "", Parser.xmlParser())
        toc.getAllElements().forEach { if (':' in it.tagName()) it.tagName(it.tagName().substringAfter(':')) }
        val entries = toc.select("navPoint").mapNotNull { point ->
            val label = point.selectFirst("navLabel")?.text().orEmpty()
            val target = HtmlReferences.resolve("toc.ncx", point.selectFirst("content")?.attr("src").orEmpty()) ?: return@mapNotNull null
            val position = destinations[target] ?: return@mapNotNull null
            if (label.isBlank()) return@mapNotNull null
            ContentsEntry(label, position.chapter, position.paragraph, position.character, point.parents().count { it.tagName() == "navPoint" })
        }.distinct().toMutableList()
        chapters.forEachIndexed { ci, chapter ->
            if (entries.none { it.chapter == ci }) {
                val at = entries.indexOfFirst { it.chapter > ci }.let { if (it < 0) entries.size else it }
                entries.add(at, ContentsEntry(chapter.title, ci))
            }
        }
        return Book(id, title, author, chapters, System.currentTimeMillis(), "kindle", entries)
    }
}
