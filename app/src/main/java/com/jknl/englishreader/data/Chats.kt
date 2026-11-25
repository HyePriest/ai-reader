package com.jknl.englishreader.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved conversation: either about the whole article, or about one selected passage.
 * The same selection always maps to the same thread, so a repeated question is answered from here.
 */
data class ChatThread(val id: String, val bookId: String, val passages: List<PassageRange>, val text: String, val location: String,
    val useArticle: Boolean, val exchanges: List<ReadingExchange>, val updatedAt: Long) {
    val whole get() = id == ARTICLE
    /** A previous answer to exactly this question in this thread, if any. */
    fun cached(question: String): Int = exchanges.indexOfFirst { it.question.trim() == question.trim() }
    companion object {
        const val ARTICLE = "article"
        fun idFor(passages: List<PassageRange>) = if (passages.isEmpty()) ARTICLE
            else contentId("passage:" + passages.joinToString(";") { "${it.chapter}.${it.paragraph}.${it.start}.${it.end}" })
        fun from(bookId: String, q: ReadingQuestion) = ChatThread(idFor(q.passages), bookId, q.passages, q.text, q.location,
            q.useArticle, emptyList(), System.currentTimeMillis())
    }
}

/** A thread found by searching saved conversations. */
data class ChatHit(val thread: ChatThread, val bookTitle: String, val exchange: Int, val snippet: String)

/** Saved conversations, one small file per book, on this device only. */
class ChatStore(context: Context) {
    private val directory = File(context.filesDir, "chats").apply { mkdirs() }
    private fun file(bookId: String): AtomicFile { require(bookId.matches(Regex("[a-f0-9]{24}|qa"))); return AtomicFile(File(directory, "$bookId.threads.json")) }

    @Synchronized fun threads(bookId: String): List<ChatThread> {
        val loaded = runCatching { decode(bookId, file(bookId).openRead().bufferedReader().use { it.readText() }) }.getOrNull()
        if (loaded != null) return loaded
        // Earlier versions kept only the whole-article conversation, as a plain list.
        val legacy = ArticleChats.legacy(directory, bookId)
        return if (legacy.isEmpty()) emptyList()
        else listOf(ChatThread(ChatThread.ARTICLE, bookId, emptyList(), "", "", true, legacy, System.currentTimeMillis()))
    }

    fun thread(bookId: String, id: String) = threads(bookId).firstOrNull { it.id == id }

    @Synchronized fun save(thread: ChatThread) {
        val rest = threads(thread.bookId).filterNot { it.id == thread.id }
        write(thread.bookId, if (thread.exchanges.isEmpty()) rest else rest + thread)
    }

    @Synchronized fun delete(bookId: String, id: String) = write(bookId, threads(bookId).filterNot { it.id == id })

    @Synchronized fun clear(bookId: String) { file(bookId).delete(); ArticleChats.dropLegacy(directory, bookId) }

    /** All books that have saved conversations. */
    fun books(): List<String> = directory.listFiles().orEmpty().mapNotNull { f ->
        f.name.removeSuffix(".bak").let { n -> if (n.endsWith(".threads.json")) n.removeSuffix(".threads.json") else if (n.endsWith(".json")) n.removeSuffix(".json") else null }
    }.filter { it.matches(Regex("[a-f0-9]{24}")) }.distinct()

    private fun write(bookId: String, threads: List<ChatThread>) {
        val atomic = file(bookId)
        if (threads.isEmpty()) { atomic.delete(); ArticleChats.dropLegacy(directory, bookId); return }
        val output = atomic.startWrite()
        try { output.write(encode(threads).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output); ArticleChats.dropLegacy(directory, bookId) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    companion object {
        fun encode(threads: List<ChatThread>): String = JSONArray().apply {
            threads.forEach { t -> put(JSONObject().put("id", t.id).put("text", t.text).put("location", t.location).put("useArticle", t.useArticle)
                .put("updatedAt", t.updatedAt)
                .put("passages", JSONArray().apply { t.passages.forEach { put(JSONArray(listOf(it.chapter, it.paragraph, it.start, it.end))) } })
                .put("exchanges", JSONArray().apply { t.exchanges.takeLast(80).forEach { put(JSONObject().put("q", it.question).put("a", it.answer)) } })) }
        }.toString()

        fun decode(bookId: String, raw: String): List<ChatThread> {
            val a = JSONArray(raw)
            return (0 until a.length()).map { i -> a.getJSONObject(i).let { t ->
                val p = t.getJSONArray("passages")
                val e = t.getJSONArray("exchanges")
                ChatThread(t.getString("id"), bookId, (0 until p.length()).map { j -> p.getJSONArray(j).let { r -> PassageRange(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)) } },
                    t.optString("text"), t.optString("location"), t.optBoolean("useArticle"),
                    (0 until e.length()).map { j -> e.getJSONObject(j).let { x -> ReadingExchange(x.getString("q"), x.getString("a")) } }, t.optLong("updatedAt"))
            } }
        }

        /** Case-insensitive search over questions, answers and quoted passages. */
        fun search(threads: List<ChatThread>, query: String): List<Pair<ChatThread, Int>> {
            val q = query.trim()
            if (q.length < 1) return emptyList()
            return threads.mapNotNull { t ->
                val index = t.exchanges.indexOfFirst { it.question.contains(q, true) || it.answer.contains(q, true) }
                when { index >= 0 -> t to index; t.text.contains(q, true) -> t to 0; else -> null }
            }
        }

        fun snippet(text: String, query: String, radius: Int = 40): String {
            val at = text.indexOf(query.trim(), ignoreCase = true)
            if (at < 0) return text.take(radius * 2).replace('\n', ' ')
            val start = (at - radius).coerceAtLeast(0)
            return (if (start > 0) "…" else "") + text.substring(start, (at + query.length + radius).coerceAtMost(text.length)).replace('\n', ' ') +
                if (at + query.length + radius < text.length) "…" else ""
        }
    }
}
