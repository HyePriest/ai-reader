package com.jknl.englishreader.data

import android.util.AtomicFile
import android.util.JsonReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ShelfChapter(val words: Int, val lengths: List<Int>, val translated: Boolean, val chineseChars: Int = 0, val chinese: Boolean = false)

/** Small, disposable metadata. The original book remains the only source of truth. */
data class ShelfBook(val id: String, val title: String, val author: String, val importedAt: Long,
    val chapters: List<ShelfChapter>) {
    val wordCount get() = chapters.sumOf { it.words }
    val isChinese get() = chapters.filter { it.words > 0 || it.chineseChars > 0 }.let { body -> body.isNotEmpty() && body.count { it.chinese } > body.size / 2 }
    val countLabel get() = if (isChinese) "${chapters.sumOf { it.chineseChars }.formatWords()} 字" else "${wordCount.formatWords()} 词"
    fun progress(position: ReadingPosition): Float {
        val total = chapters.sumOf { c -> c.lengths.sumOf { it.toLong() } }
        if (total == 0L) return 0f
        val ci = position.chapter.coerceIn(chapters.indices)
        val lengths = chapters[ci].lengths
        val pi = position.paragraph.coerceIn(0, (lengths.size - 1).coerceAtLeast(0))
        val before = chapters.take(ci).sumOf { c -> c.lengths.sumOf { it.toLong() } } +
            lengths.take(pi).sumOf { it.toLong() } + position.character.coerceIn(0, lengths.getOrElse(pi) { 0 })
        return (before.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
    companion object {
        fun from(book: Book) = ShelfBook(book.id, book.title, book.author, book.importedAt,
            book.chapters.map { ShelfChapter(it.wordCount, it.paragraphs.map { p -> p.text.length }, it.alignments.isNotEmpty(), it.characterCount, it.isChinese) })
    }
}

internal class ShelfIndex(private val directory: File) {
    fun read(source: File): ShelfBook {
        // Recover an interrupted AtomicFile write before trusting a cached fingerprint.
        AtomicFile(source).openRead().close()
        val cached = runCatching {
            val j = JSONObject(AtomicFile(File(directory, source.nameWithoutExtension + ".index")).openRead().bufferedReader().use { it.readText() })
            require(j.getInt("version") == 2 && j.getLong("modified") == source.lastModified() && j.getLong("bytes") == source.length())
            val a = j.getJSONArray("chapters")
            ShelfBook(j.getString("id"), j.getString("title"), j.getString("author"), j.getLong("importedAt"),
                (0 until a.length()).map { i -> a.getJSONObject(i).let { c ->
                    val p = c.getJSONArray("lengths")
                    ShelfChapter(c.getInt("words"), (0 until p.length()).map { p.getInt(it) }, c.getBoolean("translated"), c.optInt("chineseChars"), c.optBoolean("chinese"))
                } }).also { require(it.chapters.isNotEmpty() && it.id == source.nameWithoutExtension) }
        }.getOrNull()
        return cached ?: scan(source).also { write(source, it) }
    }

    fun write(source: File, book: ShelfBook) {
        // An index write failure must never turn a successful book save into a failed import.
        runCatching {
            val j = JSONObject().put("version", 2).put("modified", source.lastModified()).put("bytes", source.length())
                .put("id", book.id).put("title", book.title).put("author", book.author).put("importedAt", book.importedAt)
                .put("chapters", JSONArray().apply { book.chapters.forEach { c -> put(JSONObject()
                    .put("words", c.words).put("lengths", JSONArray(c.lengths)).put("translated", c.translated).put("chineseChars", c.chineseChars).put("chinese", c.chinese)) } })
            val atomic = AtomicFile(File(directory, "${book.id}.index"))
            val output = atomic.startWrite()
            try { output.write(j.toString().toByteArray()); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        }
    }

    /** First-run migration streams text; it does not allocate translations, notes or alignment trees. */
    private fun scan(source: File): ShelfBook = JsonReader(source.bufferedReader()).use { r ->
        var id = ""; var title = ""; var author = ""; var imported = 0L; var schema = 0
        val chapters = mutableListOf<ShelfChapter>()
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "schema" -> schema = r.nextInt()
            "id" -> id = r.nextString()
            "title" -> title = r.nextString()
            "author" -> author = r.nextString()
            "importedAt" -> imported = r.nextLong()
            "chapters" -> {
                r.beginArray()
                while (r.hasNext()) {
                    var words = 0; var translated = false; var han = 0; val sample = StringBuilder()
                    val lengths = mutableListOf<Int>()
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "paragraphs" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                var text = ""; var heading = false
                                r.beginObject()
                                while (r.hasNext()) when (r.nextName()) {
                                    "text" -> text = r.nextString()
                                    "heading" -> heading = r.nextBoolean()
                                    else -> r.skipValue()
                                }
                                r.endObject(); lengths += text.length
                                if (!heading) { words += TextLocator.wordCount(text); han += TextLocator.hanCount(text); if (sample.length < 20000) sample.append(text.take(1000)) }
                            }
                            r.endArray()
                        }
                        "alignments" -> { r.beginArray(); while (r.hasNext()) { translated = true; r.skipValue() }; r.endArray() }
                        else -> r.skipValue()
                    }
                    r.endObject(); chapters += ShelfChapter(words, lengths, translated, han, !translated && DocumentImporter.chinese(sample.toString()))
                }
                r.endArray()
            }
            else -> r.skipValue()
        }
        r.endObject()
        require(schema == 1 && id == source.nameWithoutExtension && chapters.isNotEmpty()) { "书目数据不完整或版本不受支持" }
        ShelfBook(id, title, author, imported, chapters)
    }
}

data class ShelfLoad(val books: List<ShelfBook>, val unreadable: List<String>)


