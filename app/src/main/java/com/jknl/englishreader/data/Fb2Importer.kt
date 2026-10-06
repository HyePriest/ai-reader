package com.jknl.englishreader.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

object Fb2Importer {
    fun read(raw: String, onCover: (String, ByteArray) -> Unit = { _, _ -> }): Book {
        require(!raw.contains("<!ENTITY", ignoreCase = true)) { "FB2 含有不支持的自定义实体" }
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        doc.getAllElements().forEach { e -> if (':' in e.tagName()) e.tagName(e.tagName().substringAfter(':')) }
        require(doc.children().any { it.tagName().substringAfter(':').equals("FictionBook", true) }) { "这不是有效的 FB2 电子书" }
        val titleInfo = doc.selectFirst("description > title-info")
        val title = titleInfo?.selectFirst("book-title")?.text().orEmpty().ifBlank { "未命名书籍" }
        val author = titleInfo?.select("author")?.joinToString("、") { e ->
            e.select("first-name, middle-name, last-name, nickname").joinToString(" ") { it.text() }
        }.orEmpty().ifBlank { "作者未注明" }
        val chapters = mutableListOf<Chapter>()
        val references = HtmlReferences(mapOf("" to doc))
        doc.select("body").forEach { body ->
            // FB2 titles and verse lines are structural, not book metadata or embedded binaries.
            body.select("title").forEach { e -> e.text(e.text()); e.tagName("h2") }
            body.select("v, subtitle, text-author").forEach { e -> e.tagName("p") }
            body.select("emphasis").forEach { e -> e.tagName("em") }
            body.select("strong").forEach { e -> e.tagName("strong") }
            val extracted = HtmlChapterReader.read(body, "fb2-${chapters.size}", if (body.attr("name") == "notes") "注释" else "正文",
                noteText = { references.note("", it) })
            if (extracted.paragraphs.isNotEmpty()) {
                val groups = mutableListOf<Paragraph>()
                fun flush() {
                    if (groups.isNotEmpty()) {
                        chapters += Chapter("fb2-${chapters.size}", groups.firstOrNull { it.heading }?.text ?: extracted.title,
                            groups.toList(), emptyList())
                        groups.clear()
                    }
                }
                extracted.paragraphs.forEach { p ->
                    if (p.heading && groups.any { !it.heading }) flush()
                    groups += p
                }
                flush()
            }
        }
        require(chapters.any { c -> c.paragraphs.any { !it.heading } }) { "FB2 没有文字正文" }
        val id = contentId("fb2:$raw")
        val cover = titleInfo?.selectFirst("coverpage image")
        val coverId = cover?.attr("l:href")?.ifBlank { cover.attr("xlink:href").ifBlank { cover.attr("href") } }?.removePrefix("#")
        doc.select("binary").firstOrNull { it.id() == coverId }?.let { binary ->
            runCatching { java.util.Base64.getMimeDecoder().decode(binary.text()) }.getOrNull()?.let { onCover(id, it) }
        }
        return Book(id, title, author, chapters, System.currentTimeMillis(), "fb2")
    }
}
