package com.jknl.englishreader.data

import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Offline text extraction. HTML scripts, remote resources and styles are never executed. */
object DocumentImporter {
    fun decode(bytes: ByteArray): String {
        val charset = when {
            bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { error("文件编码无法识别，请另存为 UTF-8 或带 BOM 的 UTF-16 文本") }
        require(text.none { it == '\u0000' }) { "这不是可读取的文本文件" }
        return text.removePrefix("\uFEFF")
    }

    fun chinese(text: String): Boolean {
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return han > 0 && (latin == 0 || han * 2 >= latin)
    }

    private val heading = Regex("^(?:第[零〇一二三四五六七八九十百千万两0-9]+[章回卷部节篇].*|(?:chapter|book|part)\\s+(?:[0-9ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)(?:[\\s.:：—-].*)?)$", RegexOption.IGNORE_CASE)

    fun text(raw: String, title: String = "导入的文本"): Book {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "文件没有正文" }
        return build(title, lines.mapIndexed { i, line ->
            val value = line.trim()
            Paragraph("p$i", value, value.length <= 100 && heading.matches(value))
        })
    }

    fun html(raw: String): Book {
        val doc = Jsoup.parse(raw)
        doc.select("script, style, nav, noscript, header nav, footer").remove()
        val selector = "p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,td,th"
        val blocks = doc.body().select(selector).filter { e -> e.children().none { it.`is`(selector) || it.select(selector).isNotEmpty() } }
        if (blocks.isEmpty()) return text(doc.body().wholeText(), doc.title().ifBlank { "导入的 HTML" })
        return build(doc.title().ifBlank { "导入的 HTML" }, blocks.mapIndexedNotNull { i, e ->
            val value = if (e.tagName() == "pre") e.wholeText() else e.text()
            value.takeIf { it.isNotBlank() }?.let { Paragraph("p$i", it, e.tagName().matches(Regex("h[1-6]"))) }
        })
    }

    private fun build(title: String, blocks: List<Paragraph>): Book {
        val chapters = mutableListOf<Chapter>()
        var current = mutableListOf<Paragraph>()
        fun flush() {
            if (current.isNotEmpty()) {
                chapters += Chapter("ch${chapters.size + 1}", current.firstOrNull { it.heading }?.text ?: "正文 ${chapters.size + 1}", current.toList(), emptyList())
                current = mutableListOf()
            }
        }
        blocks.forEach { p ->
            if (p.heading && current.any { !it.heading }) flush()
            current += p
        }
        flush()
        require(chapters.any { c -> c.paragraphs.any { !it.heading } }) { "没有找到正文段落" }
        val fingerprint = chapters.joinToString("\u0000") { c -> c.paragraphs.joinToString("\u0000") { it.text } }
        return Book(contentId("document:$fingerprint"), title, "来自本地文件", chapters, System.currentTimeMillis())
    }

    /** Adjacent language runs are a proposal, never proof of semantic correspondence. */
    fun bilingual(book: Book): Book {
        // Standalone Chinese is a readable book, not an incomplete bilingual import.
        val body = book.chapters.flatMap { it.paragraphs }.filterNot { it.heading }
        if (body.isNotEmpty() && body.count { chinese(it.text) } * 5 >= body.size * 4) return book
        val chapters = book.chapters.map { chapter ->
            if (chapter.alignments.isNotEmpty()) return@map chapter
            if (chapter.paragraphs.none { DocumentImporter.chinese(it.text) }) return@map chapter
            val body = chapter.paragraphs.filterNot { it.heading }
            require(body.any { !DocumentImporter.chinese(it.text) }) { "文件包含独立中文章节；请先导入英文，再在书内选择「导入中文文件」" }
            val chineseFirst = body.firstOrNull()?.let { DocumentImporter.chinese(it.text) } == true
            val paragraphs = mutableListOf<Paragraph>()
            val groups = mutableListOf<Alignment>()
            val english = mutableListOf<Paragraph>()
            val chinese = mutableListOf<String>()
            var chineseTitle: String? = null
            fun flush() {
                if (chinese.isNotEmpty()) {
                    require(english.isNotEmpty()) { "《${chapter.title}》有未配对的中文；请分别导入英文与中文，在预览中选择对应章节" }
                    groups += Alignment("alternating-${groups.size}", english.map { SourceRange(it.id, 0, it.text.length) }, chinese.joinToString("\n\n"), "交替双语（待核对）")
                }
                english.clear(); chinese.clear()
            }
            chapter.paragraphs.forEach { p ->
                if (p.heading) {
                    flush()
                    if (DocumentImporter.chinese(p.text)) {
                        val title = paragraphs.lastOrNull()?.takeIf { it.heading }
                        if (title != null) groups += Alignment("title-${groups.size}", listOf(SourceRange(title.id, 0, title.text.length)), p.text, "交替双语（待核对）")
                        else chineseTitle = p.text
                    } else {
                        paragraphs += p
                        chineseTitle?.let { zh -> groups += Alignment("title-${groups.size}", listOf(SourceRange(p.id, 0, p.text.length)), zh, "交替双语（待核对）") }
                        chineseTitle = null
                    }
                } else if (DocumentImporter.chinese(p.text)) {
                    if (chineseFirst && english.isNotEmpty()) flush()
                    chinese += p.text
                } else {
                    if (!chineseFirst && chinese.isNotEmpty()) flush()
                    paragraphs += p
                    english += p
                }
            }
            flush()
            require(paragraphs.any { !it.heading }) { "文件包含独立中文章节；请先导入英文，再在书内选择「导入中文文件」" }
            chapter.copy(title = paragraphs.firstOrNull { it.heading }?.text ?: chapter.title, paragraphs = paragraphs, alignments = groups).also(Alignments::validate)
        }
        return book.copy(chapters = chapters)
    }
}

