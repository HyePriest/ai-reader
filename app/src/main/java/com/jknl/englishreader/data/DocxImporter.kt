package com.jknl.englishreader.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.zip.ZipInputStream

object DocxImporter {
    fun read(bytes: ByteArray, fileName: String): Book {
        var document: String? = null
        val noteFiles = mutableMapOf<String, String>()
        var title = fileName.substringBeforeLast('.')
        var author = "作者未注明"
        var total = 0L
        var count = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(++count <= 6000) { "DOCX 内部文件过多" }
                val data = zip.readLimited(16 * 1024 * 1024)
                total += data.size
                require(total <= 100L * 1024 * 1024) { "DOCX 解压后过大" }
                when (entry.name) {
                    "word/document.xml" -> document = DocumentImporter.decode(data)
                    "word/footnotes.xml", "word/endnotes.xml" -> noteFiles[entry.name.substringAfterLast('/').substringBefore('.').removeSuffix("s")] = DocumentImporter.decode(data)
                    "docProps/core.xml" -> {
                        val metadata = Jsoup.parse(DocumentImporter.decode(data), "", Parser.xmlParser())
                        metadata.getAllElements().forEach { e -> when (e.tagName().substringAfter(':')) {
                            "title" -> if (e.text().isNotBlank()) title = e.text()
                            "creator" -> if (e.text().isNotBlank()) author = e.text()
                        } }
                    }
                }
                entry = zip.nextEntry
            }
        }
        val raw = requireNotNull(document) { "DOCX 缺少 word/document.xml" }
        require(!raw.contains("<!ENTITY", ignoreCase = true)) { "DOCX 含有不支持的自定义实体" }
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        val notes = linkedMapOf<Pair<String, String>, String>()
        noteFiles.forEach { (kind, source) ->
            require(!source.contains("<!ENTITY", ignoreCase = true)) { "DOCX 注释含有不支持的自定义实体" }
            Jsoup.parse(source, "", Parser.xmlParser()).getAllElements().filter { it.tagName().substringAfter(':') == kind }.forEach { note ->
                val id = note.attr("w:id").ifBlank { note.attr("id") }
                if ((id.toIntOrNull() ?: -1) < 0 || note.attr("w:type") in setOf("separator", "continuationSeparator")) return@forEach
                val body = note.getAllElements().filter { it.tagName().substringAfter(':') == "p" }.joinToString("\n\n") { p ->
                    buildString { p.getAllElements().forEach { e -> when (e.tagName().substringAfter(':')) {
                        "t" -> append(e.wholeText()); "tab" -> append('\t'); "br", "cr" -> append('\n')
                    } } }
                }.trim()
                if (body.isNotBlank()) notes[kind to id] = body
            }
        }
        val paragraphs = doc.getAllElements().filter { it.tagName().substringAfter(':') == "p" }.mapIndexedNotNull { index, p ->
            val text = StringBuilder()
            val annotations = mutableListOf<BookAnnotation>()
            p.getAllElements().forEach { e -> when (e.tagName().substringAfter(':')) {
                "t" -> text.append(e.wholeText())
                "tab" -> text.append('\t')
                "br", "cr" -> text.append('\n')
                "footnoteReference", "endnoteReference" -> {
                    val kind = e.tagName().substringAfter(':').removeSuffix("Reference")
                    val id = e.attr("w:id").ifBlank { e.attr("id") }
                    val start = text.length
                    text.append("〔$id〕")
                    notes[kind to id]?.let { annotations += BookAnnotation(start, text.length, it) }
                }
            } }
            val style = p.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "pStyle" }
            val styleName = style?.attr("w:val").orEmpty().ifBlank { style?.attr("val").orEmpty() }
            text.toString().takeIf { it.isNotBlank() }?.let {
                Paragraph("p$index", it, styleName.matches(Regex("(?:Heading[1-9]|Title|标题[1-9]?)", RegexOption.IGNORE_CASE)), annotations = annotations)
            }
        }
        val book = DocumentImporter.build(title, paragraphs).copy(author = author, sourceFormat = "docx")
        if (notes.isEmpty()) return book
        val appendix = Chapter("docx-notes", "注释", notes.entries.mapIndexed { i, e -> Paragraph("note-$i", "〔${e.key.second}〕 ${e.value}") }, emptyList())
        return book.copy(chapters = book.chapters + appendix)
    }
}
