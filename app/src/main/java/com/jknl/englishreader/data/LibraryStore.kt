package com.jknl.englishreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Versioned, atomic, per-book files. Keep migration independent of the UI. */
class LibraryStore(private val context: Context) {
    init { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext) }
    private val directory = File(context.filesDir, "library").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    // Keep recent immutable books ready when returning from the shelf; saving replaces the entry.
    private val opened = android.util.LruCache<String, Book>(2)
    fun cached(id: String): Book? = opened.get(id)

    fun shelf(): ShelfLoad {
        val failures = mutableListOf<String>()
        val index = ShelfIndex(directory)
        val books = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(".json") }.distinct().mapNotNull { name ->
                try { index.read(File(directory, name)) }
                catch (_: Exception) { failures += name; null }
            }.sortedByDescending { it.importedAt }
        return ShelfLoad(books, failures)
    }

    @Synchronized fun open(id: String): Book {
        require(id.matches(Regex("[a-f0-9]{24}")))
        cached(id)?.let { return it }
        return AtomicFile(File(directory, "$id.json")).openRead().bufferedReader().use { BookJsonReader.read(it) }
            .also { require(it.id == id); it.wordCount; opened.put(id, it) }
    }

    fun position(book: ShelfBook) = ReadingPosition(
        preferences.getInt("${book.id}.chapter", 0).coerceIn(book.chapters.indices),
        preferences.getInt("${book.id}.paragraph", 0).coerceAtLeast(0),
        preferences.getInt("${book.id}.character", 0).coerceAtLeast(0)
    )

    fun load(): List<Book> {
        val names = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(".json") }.distinct()
        return names.map { name -> decode(AtomicFile(File(directory, name)).openRead().bufferedReader().use { it.readText() }).also { it.wordCount } }
            .sortedByDescending { it.importedAt }
    }

    fun import(uri: Uri): Book = importBytes(readUri(uri), fileName(uri))

    private fun readUri(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readLimited(32 * 1024 * 1024) }
        ?: error("无法打开选中的文件")

    /** An Economist issue becomes separate articles; every other file stays one book. */
    fun importAny(uri: Uri): ImportResult {
        val bytes = readUri(uri)
        if (bytes.size >= 2 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte())
            EconomistIssue.readWithImages(bytes)?.let { return ImportResult.Issue(it.articles, it.images) }
        // Converted past papers often start with an HTML comment, so only binary formats are excluded here.
        else if (bytes.isNotEmpty() && bytes.size < 2 * 1024 * 1024 && !bytes.take(5).toByteArray().toString(Charsets.US_ASCII).startsWith("%PDF")) {
            val text = DocumentImporter.decode(bytes)
            if (ExamPaper.looksLike(text)) return ImportResult.Issue(ExamPaper.read(text))
        }
        return ImportResult.Single(importBytes(bytes, fileName(uri)))
    }

    /** Saves articles that are not on the device yet. Existing ones keep their progress and chats. */
    @Synchronized fun saveNew(articles: List<Book>): List<Book> = articles.filter { !File(directory, "${it.id}.json").exists() }.onEach { save(it) }

    fun saveImage(id: String, bytes: ByteArray) { runCatching { BookCovers(context).save(id, bytes) } }

    fun exists(id: String) = id.matches(Regex("[a-f0-9]{24}")) && File(directory, "$id.json").exists()

    /** Removes a book or article with its progress, reading position and saved AI conversation. */
    @Synchronized fun delete(id: String) {
        require(id.matches(Regex("[a-f0-9]{24}")))
        listOf("json", "index", "undo", "pdf").forEach { AtomicFile(File(directory, "$id.$it")).delete() }
        opened.remove(id)
        preferences.edit().remove("$id.chapter").remove("$id.paragraph").remove("$id.character").apply()
        runCatching { BookCovers(context).remove(id) }
        ArticleChats(context).clear(id)
    }

    private fun importBytes(bytes: ByteArray, name: String): Book {
        val book = BookFiles.read(bytes.inputStream(), name) { id, cover ->
            val covers = BookCovers(context)
            if (!covers.file(id).exists()) runCatching { covers.save(id, cover) }
        }
        if (book.sourceFormat == "pdf") {
            val source = AtomicFile(File(directory, "${book.id}.pdf"))
            val output = source.startWrite()
            try { output.write(bytes); source.finishWrite(output) }
            catch (e: Exception) { source.failWrite(output); throw e }
            val covers = BookCovers(context)
            if (!covers.file(book.id).exists()) runCatching { covers.savePdf(book.id, File(directory, "${book.id}.pdf")) }
        }
        return book
    }

    fun sample(): Book = context.assets.open("a-window-in-the-rain.epub").use { EpubImporter().read(it) }

    fun fileName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "导入的文本"

    /** One guarded undo snapshot. A later change invalidates restoration instead of erasing it. */
    fun saveWithUndo(before: Book, after: Book) {
        require(before.id == after.id) { "不能替换另一本书" }
        require(before.id.matches(Regex("[a-f0-9]{24}")))
        val current = decode(AtomicFile(File(directory, "${before.id}.json")).openRead().bufferedReader().use { it.readText() })
        require(encode(current) == encode(before)) { "书籍已经变化，请重新打开导入预览" }
        after.chapters.forEach(Alignments::validate)
        val snapshot = JSONObject().put("before", encode(before)).put("afterHash", contentId(encode(after))).toString()
        val undo = AtomicFile(File(directory, "${before.id}.undo"))
        val stream = undo.startWrite()
        try { stream.write(snapshot.toByteArray(Charsets.UTF_8)); undo.finishWrite(stream) }
        catch (e: Exception) { undo.failWrite(stream); throw e }
        save(after)
    }

    fun undoCandidate(book: Book): Book {
        require(book.id.matches(Regex("[a-f0-9]{24}")))
        val snapshot = try { JSONObject(AtomicFile(File(directory, "${book.id}.undo")).openRead().bufferedReader().use { it.readText() }) }
            catch (_: java.io.FileNotFoundException) { error("没有可撤销的中文修改") }
        require(snapshot.getString("afterHash") == contentId(encode(book))) { "书籍已有后续变化，不能整书回退；请使用局部编辑" }
        return decode(snapshot.getString("before")).also { require(it.id == book.id) }
    }

    fun clearUndo(book: Book) { AtomicFile(File(directory, "${book.id}.undo")).delete() }

    @Synchronized fun save(book: Book) {
        book.chapters.forEach(Alignments::validate)
        require(book.id.matches(Regex("[a-f0-9]{24}")))
        val atomic = AtomicFile(File(directory, "${book.id}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(encode(book).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        ShelfIndex(directory).write(File(directory, "${book.id}.json"), ShelfBook.from(book))
        opened.put(book.id, book)
    }

    fun position(book: Book) = ReadingPosition(
        preferences.getInt("${book.id}.chapter", 0).coerceIn(book.chapters.indices),
        preferences.getInt("${book.id}.paragraph", 0),
        preferences.getInt("${book.id}.character", 0)
    )

    fun savePosition(book: Book, position: ReadingPosition) {
        preferences.edit().putInt("${book.id}.chapter", position.chapter)
            .putInt("${book.id}.paragraph", position.paragraph).putInt("${book.id}.character", position.character).apply()
    }

    fun settings() = ReaderSettings(
        preferences.getFloat("size", 21f).coerceIn(16f, 30f),
        preferences.getFloat("spacing", 1.7f).coerceIn(1.3f, 2.2f),
        preferences.getString("palette", "paper") ?: "paper",
        preferences.getBoolean("serif", true)
    )

    fun saveSettings(settings: ReaderSettings) {
        preferences.edit().putFloat("size", settings.size).putFloat("spacing", settings.spacing)
            .putString("palette", settings.palette).putBoolean("serif", settings.serif).apply()
    }

    private fun array(items: Iterable<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    fun encode(book: Book): String = JSONObject().apply {
        put("schema", 1); put("id", book.id); put("title", book.title); put("author", book.author); put("importedAt", book.importedAt)
        put("sourceFormat", book.sourceFormat)
        if (book.collection.isNotEmpty()) put("collection", book.collection)
        if (book.section.isNotEmpty()) put("section", book.section)
        if (book.subtitle.isNotEmpty()) put("subtitle", book.subtitle)
        if (book.source.isNotEmpty()) put("source", book.source)
        if (book.titleZh.isNotEmpty()) put("titleZh", book.titleZh)
        if (book.questions.isNotEmpty()) put("questions", ExamQuestion.encode(book.questions))
        put("contents", array(book.contents.map { e -> JSONObject().put("title", e.title).put("chapter", e.chapter)
            .put("paragraph", e.paragraph).put("character", e.character).put("depth", e.depth) }))
        put("chapters", array(book.chapters.map { chapter -> JSONObject().apply {
            put("id", chapter.id); put("title", chapter.title)
            put("paragraphs", array(chapter.paragraphs.map { p -> JSONObject().apply {
                put("id", p.id); put("text", p.text); put("heading", p.heading)
                p.pdfPage?.let { put("pdfPage", it) }
                put("marks", array(p.marks.map { m -> JSONObject().put("start", m.start).put("end", m.end).put("kind", m.kind) }))
                put("units", array(p.units.map { u -> JSONObject().put("start", u.start).put("end", u.end) }))
                put("notes", array(p.notes.map { n -> JSONObject().put("start", n.start).put("end", n.end).put("term", n.term).put("meaning", n.meaning) }))
                put("annotations", array(p.annotations.map { n -> JSONObject().put("start", n.start).put("end", n.end)
                    .put("text", n.text).put("title", n.title).put("source", n.source) }))
            } }))
            put("alignments", array(chapter.alignments.map { a -> JSONObject().apply {
                put("id", a.id); put("chinese", a.chinese); put("source", a.source)
                put("ranges", array(a.ranges.map { r -> JSONObject().put("paragraph", r.paragraph).put("start", r.start).put("end", r.end) }))
            } }))
        } }))
    }.toString()

    fun decode(raw: String): Book {
        val root = JSONObject(raw)
        require(root.getInt("schema") == 1) { "这本书的数据版本暂不支持，请保留原文件" }
        return Book(root.getString("id"), root.getString("title"), root.getString("author"),
            root.getJSONArray("chapters").objects().map { c ->
                Chapter(c.getString("id"), c.getString("title"), c.getJSONArray("paragraphs").objects().map { p ->
                    Paragraph(p.getString("id"), p.getString("text"), p.getBoolean("heading"), p.getJSONArray("marks").objects().map { m ->
                        Mark(m.getInt("start"), m.getInt("end"), m.getString("kind"))
                    }, (p.optJSONArray("units") ?: JSONArray()).objects().map { u -> ReadingUnit(u.getInt("start"), u.getInt("end")) },
                        (p.optJSONArray("notes") ?: JSONArray()).objects().map { n -> WordNote(n.getInt("start"), n.getInt("end"), n.getString("term"), n.getString("meaning")) },
                        (p.optJSONArray("annotations") ?: JSONArray()).objects().map { n -> BookAnnotation(n.getInt("start"), n.getInt("end"), n.getString("text"), n.optString("title", ""), n.optString("source", "")) },
                        if (p.has("pdfPage")) p.getInt("pdfPage").also { require(it in 0..2999) } else null)
                }, c.getJSONArray("alignments").objects().map { a ->
                    Alignment(a.getString("id"), a.getJSONArray("ranges").objects().map { r ->
                        SourceRange(r.getString("paragraph"), r.getInt("start"), r.getInt("end"))
                    }, a.getString("chinese"), a.getString("source"))
                }).also(Alignments::validate)
            }, root.getLong("importedAt"), root.optString("sourceFormat", ""),
            (root.optJSONArray("contents") ?: JSONArray()).objects().map { e -> ContentsEntry(e.getString("title"), e.getInt("chapter"),
                e.optInt("paragraph"), e.optInt("character"), e.optInt("depth")) },
            root.optString("collection", ""), root.optString("section", ""), root.optString("subtitle", ""), root.optString("source", ""),
            root.optString("titleZh", ""), root.optJSONArray("questions")?.let { ExamQuestion.decode(it) } ?: emptyList())
    }
}

sealed interface ImportResult {
    data class Single(val book: Book) : ImportResult
    data class Issue(val articles: List<Book>, val images: Map<String, ByteArray> = emptyMap()) : ImportResult
}

/** One saved whole-article conversation per article, kept on the device only. */
class ArticleChats(context: Context) {
    private val directory = File(context.filesDir, "chats").apply { mkdirs() }
    private fun file(id: String): AtomicFile { require(id.matches(Regex("[a-f0-9]{24}|qa"))); return AtomicFile(File(directory, "$id.json")) }
    fun read(id: String): List<ReadingExchange> = runCatching {
        val a = JSONArray(file(id).openRead().bufferedReader().use { it.readText() })
        (0 until a.length()).map { a.getJSONObject(it).let { e -> ReadingExchange(e.getString("q"), e.getString("a")) } }
    }.getOrDefault(emptyList())
    fun write(id: String, history: List<ReadingExchange>) {
        val atomic = file(id)
        val output = atomic.startWrite()
        try {
            val a = JSONArray().apply { history.takeLast(60).forEach { put(JSONObject().put("q", it.question).put("a", it.answer)) } }
            output.write(a.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun clear(id: String) { file(id).delete() }
}
