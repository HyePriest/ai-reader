package com.jknl.englishreader.data

import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/** Offline text extraction. HTML scripts, remote resources and styles are never executed. */
object DocumentImporter {
    fun decode(bytes: ByteArray): String {
        val prefix = bytes.take(1024).toByteArray().toString(Charsets.ISO_8859_1)
        val declared = if (prefix.trimStart().startsWith("<")) Regex("(?:encoding|charset)\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(prefix)?.groupValues?.get(1) else null
        fun utf16Score(encoding: Charset): Int = bytes.toString(encoding).sumOf { c -> when {
            c.code < 32 && c !in "\n\r\t\u000c" -> -100
            c in "\n\r\t" -> 10
            c in ' '..'~' -> 2
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> 1
            else -> 0
        } }
        val charset = when {
            bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> Charsets.UTF_16BE
            bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> Charsets.UTF_8
            bytes.size >= 4 && bytes[1] == 0.toByte() && bytes[3] == 0.toByte() -> Charsets.UTF_16LE
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[2] == 0.toByte() -> Charsets.UTF_16BE
            bytes.size >= 8 && bytes.size % 2 == 0 && bytes.count { it == 0.toByte() } >= 2 ->
                if (utf16Score(Charsets.UTF_16LE) > utf16Score(Charsets.UTF_16BE)) Charsets.UTF_16LE else Charsets.UTF_16BE
            declared != null -> runCatching { Charset.forName(declared) }.getOrElse { error("不支持文件声明的编码：$declared") }
            else -> Charsets.UTF_8
        }
        fun decoded(encoding: Charset) = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val text = try { decoded(charset) } catch (_: Exception) {
            if (charset != Charsets.UTF_8 || declared != null) error("文件编码与声明不符，无法读取")
            val legacy = runCatching { decoded(Charset.forName("GB18030")) }.getOrNull()
            if (legacy == null || legacy.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
                error("文件编码无法识别，请另存为 UTF-8、GBK 或 UTF-16 文本")
            legacy
        }
        require(text.none { it.code < 32 && it !in "\n\r\t\u000c" }) { "这不是可读取的文本文件" }
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
            Paragraph("p$i", value, value.length <= 100 && (heading.matches(value) ||
                value.matches(Regex("(?:前言|序言|序|目录|后记|附录|注释|版权页|致谢|Preface|Foreword|Contents|Acknowledgments|Notes)", RegexOption.IGNORE_CASE))))
        })
    }

    fun html(raw: String): Book {
        val doc = Jsoup.parse(raw)
        val references = HtmlReferences(mapOf("" to doc.body()))
        val chapter = HtmlChapterReader.read(doc.body(), "html", "正文", noteText = { references.note("", it) })
        if (chapter.alignments.isNotEmpty()) return Book(contentId("html:$raw"), doc.title().ifBlank { "导入的 HTML" },
            "来自本地文件", listOf(chapter), System.currentTimeMillis())
        return build(doc.title().ifBlank { "导入的 HTML" }, chapter.paragraphs)
    }

    internal fun build(title: String, blocks: List<Paragraph>): Book {
        val chapters = mutableListOf<Chapter>()
        var current = mutableListOf<Paragraph>()
        fun flush() {
            if (current.isNotEmpty()) {
                chapters += Chapter("ch${chapters.size + 1}", current.firstOrNull { it.heading }?.text ?: "正文", current.toList(), emptyList())
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
    fun bilingual(book: Book): Book = try { bilingualCandidate(book) } catch (_: IllegalArgumentException) {
        // A mixed-language book must remain readable even when automatic pairing is ambiguous.
        book
    }

    private fun bilingualCandidate(book: Book): Book {
        // Note bodies may use another language. Never remove them as guessed translations.
        if (book.chapters.any { c -> c.paragraphs.any { it.annotations.isNotEmpty() } } ||
            book.contents.any { it.paragraph != 0 || it.character != 0 }) return book
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
            require(chineseTitle == null) { "有未配对的中文标题" }
            require(paragraphs.any { !it.heading }) { "文件包含独立中文章节；请先导入英文，再在书内选择「导入中文文件」" }
            chapter.copy(title = paragraphs.firstOrNull { it.heading }?.text ?: chapter.title, paragraphs = paragraphs, alignments = groups).also(Alignments::validate)
        }
        return book.copy(chapters = chapters)
    }
}

