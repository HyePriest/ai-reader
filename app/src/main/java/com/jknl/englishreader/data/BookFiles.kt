package com.jknl.englishreader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size() + n <= limit) { "文件过大，暂不支持" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

object BookFiles {
    fun read(input: InputStream): Book = read(input, "导入的文本")
    fun read(input: InputStream, fileName: String, onCover: (String, ByteArray) -> Unit = { _, _ -> }): Book = DocumentImporter.bilingual(readDocument(input, fileName, onCover))

    /** Raw extraction is also used for a separate Chinese book; do not strip its paragraphs. */
    fun readDocument(input: InputStream, fileName: String = "导入的文本", onCover: (String, ByteArray) -> Unit = { _, _ -> }): Book {
        val bytes = input.readLimited(32 * 1024 * 1024)
        if (bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "{\\rtf") return RtfImporter.read(bytes, fileName)
        if (bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-") return PdfImporter.read(bytes, fileName)
        if (bytes.size >= 68 && bytes.copyOfRange(60, 68).toString(Charsets.US_ASCII) in setOf("BOOKMOBI", "TEXtREAd"))
            return MobiImporter.read(bytes, fileName, onCover)
        if (bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()) {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension == "docx") return DocxImporter.read(bytes, fileName)
            if (extension == "fb2zip" || fileName.endsWith(".fb2.zip", ignoreCase = true)) {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                    var count = 0
                    var total = 0L
                    var entry = zip.nextEntry
                    while (entry != null) {
                        require(++count <= 6000) { "FB2 压缩包内部文件过多" }
                        val data = zip.readLimited(32 * 1024 * 1024)
                        total += data.size
                        require(total <= 100L * 1024 * 1024) { "FB2 压缩包解压后过大" }
                        if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true))
                            return Fb2Importer.read(DocumentImporter.decode(data), onCover)
                        entry = zip.nextEntry
                    }
                }
                error("压缩包内没有 FB2 正文")
            }
            return EpubImporter().read(bytes.inputStream(), onCover)
        }
        val raw = DocumentImporter.decode(bytes).trimStart()
        return when {
            Regex("<(?:[A-Za-z0-9_-]+:)?FictionBook(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(raw.take(4096)) -> Fb2Importer.read(raw, onCover)
            raw.startsWith("<") -> if (raw.contains("SENTENCE_BOUNDS") || raw.contains("TRANSLATIONS")) HtmlBookImporter().read(raw) else DocumentImporter.html(raw)
            raw.startsWith("{") -> readJson(JSONObject(raw))
            else -> DocumentImporter.text(raw, fileName.substringBeforeLast('.'))
        }
    }

    fun readJson(root: JSONObject): Book {
        require(root.optString("format") == "englishreader.book.v1") {
            if (root.optString("format") == "englishreader.translations.v1") "这是译文补充文件，请在对应书籍的「章节与中文」中导入。" else "书籍 JSON 的 format 应为 englishreader.book.v1"
        }
        val title = root.getString("title").trim().also { require(it.isNotBlank()) { "书名不能为空" } }
        val language = root.optString("language", "en")
        require(language in setOf("en", "zh")) { "language 只支持 en 或 zh" }
        val entries = root.getJSONArray("chapters")
        require(entries.length() > 0) { "书籍至少需要一章" }
        val chapters = (0 until entries.length()).map { ci ->
            val c = entries.getJSONObject(ci)
            val titleEn = c.getString("title")
            val paragraphs = mutableListOf(Paragraph("@title", titleEn, heading = true))
            val alignments = mutableListOf<Alignment>()
            val ps = c.getJSONArray("paragraphs")
            require(ps.length() > 0) { "章节没有正文" }
            for (pi in 0 until ps.length()) {
                val p = ps.getJSONObject(pi)
                val id = p.optString("id", "p${pi + 1}")
                if (language == "zh") {
                    val body = p.getString("text").also { require(it.isNotBlank()) { "$id 正文为空" } }
                    paragraphs += Paragraph(id, body, annotations = BookAnnotations.read(p, body, id))
                    continue
                }
                val sentences = p.getJSONArray("sentences")
                require(sentences.length() > 0) { "段落 $id 没有句子" }
                val text = StringBuilder()
                val units = mutableListOf<ReadingUnit>()
                val annotations = mutableListOf<BookAnnotation>()
                for (si in 0 until sentences.length()) {
                    val s = sentences.getJSONObject(si)
                    val en = s.getString("en").trim().also { require(it.isNotBlank()) { "$id 有空英文" } }
                    if (text.isNotEmpty()) text.append(' ')
                    val start = text.length
                    text.append(en)
                    units += ReadingUnit(start, text.length)
                    annotations += BookAnnotations.read(s, en, "$id:${si + 1}", start)
                    val origin = s.optString("translation_source", "unspecified")
                    require(origin in setOf("published", "ai", "unspecified")) { "$id:$si 的 translation_source 应为 published 或 ai" }
                    if (!s.isNull("zh") && s.getString("zh").isNotBlank()) alignments += Alignment("$id:$si", listOf(SourceRange(id, start, text.length)), s.getString("zh"),
                        when (origin) { "ai" -> "AI 补译"; "published" -> "原译本"; else -> "书籍 JSON" })
                }
                annotations += BookAnnotations.read(p, text.toString(), id)
                paragraphs += Paragraph(id, text.toString(), units = units, annotations = annotations)
            }
            Chapter(c.optString("id", "ch${ci + 1}"), titleEn, paragraphs, alignments).also(Alignments::validate)
        }
        require(chapters.map { it.id }.distinct().size == chapters.size) { "章节 ID 重复" }
        val fingerprint = title + chapters.joinToString("\u0000") { c -> c.id + c.paragraphs.joinToString("\u0000") { it.id + it.text } }
        return Book(contentId("json:$fingerprint"), title, root.optString("author", "作者未注明"), chapters, System.currentTimeMillis())
    }
}

data class TranslationImport(val book: Book, val count: Int, val conflicts: Int, val chapterTitles: List<String>)

/** Exports original context plus stable references; a translation response only fills zh. */
object TranslationFiles {
    fun exportChapter(book: Book, chapterIndex: Int): String {
        val c = book.chapters[chapterIndex]
        val paragraphs = JSONArray()
        c.paragraphs.filterNot { it.heading }.forEach { p ->
            val sentences = JSONArray()
            val units = if (p.units.isNotEmpty()) p.units else splitUnits(p.text)
            units.forEach { u ->
                // Already translated ranges are deliberately excluded from the fill-in list.
                if (c.alignments.none { a -> a.ranges.any { Alignments.overlaps(it, SourceRange(p.id, u.start, u.end)) } }) {
                    sentences.put(JSONObject().put("start", u.start).put("end", u.end)
                        .put("en", p.text.substring(u.start, u.end)).put("zh", JSONObject.NULL))
                }
            }
            if (sentences.length() > 0) paragraphs.put(JSONObject().put("id", p.id).put("text", p.text)
                .put("sourceHash", contentId(p.text)).put("sentences", sentences))
        }
        require(paragraphs.length() > 0) { "本章已经配有中文，没有待译内容" }
        return JSONObject().put("format", "englishreader.translations.v1").put("bookId", book.id).put("title", book.title)
            .put("instruction", "先通读 context 中的本章全文，再结合上下文翻译，只填写 zh。保留 format、bookId、章节和段落 id、start、end、sourceHash；未译的 zh 保留 null。返回时可省略 context、text、en、title、instruction，避免重复输出英文。不要生成网页代码。")
            .put("chapters", JSONArray().put(JSONObject().put("id", c.id).put("title", c.title)
                .put("context", c.paragraphs.joinToString("\n\n") { it.text }).put("paragraphs", paragraphs))).toString(2)
    }

    fun prepare(book: Book, raw: String): TranslationImport {
        val root = JSONObject(raw.removePrefix("\uFEFF"))
        require(root.getString("format") == "englishreader.translations.v1" && root.getString("bookId") == book.id) { "这个译文文件不属于当前书籍，请打开正确的书再导入" }
        val updates = mutableMapOf<String, MutableList<Alignment>>()
        val entries = root.getJSONArray("chapters")
        for (ci in 0 until entries.length()) {
            val c = entries.getJSONObject(ci)
            val chapter = requireNotNull(book.chapters.find { it.id == c.getString("id") }) { "译文指向不存在的章节" }
            val ps = c.getJSONArray("paragraphs")
            for (pi in 0 until ps.length()) {
                val p = ps.getJSONObject(pi)
                val paragraph = requireNotNull(chapter.paragraphs.find { it.id == p.getString("id") }) { "译文指向不存在的段落" }
                require(contentId(paragraph.text) == p.getString("sourceHash")) { "英文正文版本不同，请重新导出本章待译文件" }
                val ss = p.getJSONArray("sentences")
                for (si in 0 until ss.length()) {
                    val s = ss.getJSONObject(si)
                    val start = s.getInt("start"); val end = s.getInt("end")
                    val knownUnits = if (paragraph.units.isNotEmpty()) paragraph.units else splitUnits(paragraph.text)
                    require(ReadingUnit(start, end) in knownUnits && (!s.has("en") || paragraph.text.substring(start, end) == s.getString("en"))) { "译文文件中的英文或位置被改动，未导入" }
                    if (s.isNull("zh") || s.getString("zh").isBlank()) continue
                    val alignment = Alignment("import:${paragraph.id}:$start:$end", listOf(SourceRange(paragraph.id, start, end)), s.getString("zh"), "导入中文")
                    updates.getOrPut(chapter.id) { mutableListOf() }.add(alignment)
                }
            }
        }
        require(updates.isNotEmpty()) { "文件中没有填好的中文；请先填写 zh，再导入" }
        var conflicts = 0
        val updated = book.chapters.map { c ->
            val additions = updates[c.id] ?: return@map c
            Alignments.validate(c.copy(alignments = additions))
            val old = c.alignments.filter { a -> a.ranges.any { r -> additions.any { n -> n.ranges.any { Alignments.overlaps(r, it) } } } }
            conflicts += old.size
            c.copy(alignments = c.alignments - old.toSet() + additions).also(Alignments::validate)
        }
        return TranslationImport(book.copy(chapters = updated), updates.values.sumOf { it.size }, conflicts, book.chapters.filter { it.id in updates }.map { it.title })
    }

    private fun splitUnits(text: String): List<ReadingUnit> {
        val units = mutableListOf<ReadingUnit>()
        var offset = 0
        while (offset < text.length) {
            while (offset < text.length && text[offset].isWhitespace()) offset++
            if (offset >= text.length) break
            val range = TextLocator.sentenceAt(text, offset)
            if (range.isEmpty()) break
            units += ReadingUnit(range.first, range.last + 1)
            offset = range.last + 1
        }
        return units
    }
}
