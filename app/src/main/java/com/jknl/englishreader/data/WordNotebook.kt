package com.jknl.englishreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class WordOrigin(val bookId: String = "", val bookTitle: String = "", val chapterId: String = "", val chapterTitle: String = "")

data class WordRecord(val id: String, val cacheKey: String, val query: WordQuery, val origin: WordOrigin,
    val response: String, val model: String, val goal: LearningGoal, val promptVersion: String, val viewedAt: Long,
    val lookupCount: Int = 1) {
    val explanation: WordExplanation by lazy { WordExplanation.parse(response) }
    val identity: String get() = identity(query, origin)
    val lemmaKey: String get() = explanation.lemma.trim().lowercase(Locale.ROOT)
    fun json(): JSONObject = JSONObject().put("id", id).put("cacheKey", cacheKey).put("query", query.json())
        .put("origin", JSONObject().put("bookId", origin.bookId).put("bookTitle", origin.bookTitle).put("chapterId", origin.chapterId).put("chapterTitle", origin.chapterTitle))
        .put("response", JSONObject(response)).put("model", model).put("goal", goal.name).put("promptVersion", promptVersion)
        .put("viewedAt", viewedAt).put("lookupCount", lookupCount)
    companion object {
        fun identity(query: WordQuery, origin: WordOrigin): String = contentId(JSONArray()
            .put(query.word.lowercase(Locale.ROOT)).put(query.sentence).put(query.start)
            .put(origin.bookId).put(origin.chapterId).toString())
        fun create(cacheKey: String, query: WordQuery, origin: WordOrigin, response: String, model: String, goal: LearningGoal) =
            WordRecord(identity(query, origin), cacheKey, query, origin, response, model, goal, WordPrompt.VERSION, System.currentTimeMillis())
        fun read(o: JSONObject): WordRecord {
            val q = o.getJSONObject("query"); val s = o.getJSONObject("origin")
            return WordRecord(o.getString("id"), o.getString("cacheKey"), WordQuery(q.getString("word"), q.getString("sentence"), q.getInt("word_start_utf16")),
                WordOrigin(s.getString("bookId"), s.getString("bookTitle"), s.getString("chapterId"), s.getString("chapterTitle")),
                o.getJSONObject("response").toString(), o.getString("model"), LearningGoal.valueOf(o.getString("goal")), o.getString("promptVersion"),
                o.getLong("viewedAt"), o.optInt("lookupCount", 1)).also { it.explanation }
        }
    }
}

data class NotebookSnapshot(val saved: List<WordRecord> = emptyList(), val recent: List<WordRecord> = emptyList())

/** Indexed row storage: lookups no longer rewrite/parse every saved explanation. Legacy JSON stays intact. */
class WordNotebook(context: Context, name: String = "word-notebook.json") {
    init { require(name.matches(Regex("[A-Za-z0-9._-]+"))) }
    private val legacy = AtomicFile(File(context.filesDir, name))
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "$name.sqlite", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries (identity TEXT PRIMARY KEY, id TEXT NOT NULL, cache_key TEXT NOT NULL, viewed INTEGER NOT NULL, recent INTEGER NOT NULL DEFAULT 0, saved INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX entries_recent ON entries(recent, viewed DESC)")
            db.execSQL("CREATE INDEX entries_cache ON entries(cache_key)")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("生词本版本暂不支持，原数据已保留。")
    }
    private fun <T> database(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        try {
            val db = helper.writableDatabase
            migrate(db)
            block(db)
        } finally { helper.close() }
    }
    private fun migrate(db: SQLiteDatabase) {
        db.rawQuery("SELECT value FROM metadata WHERE key='legacy-imported'", null).use { if (it.moveToFirst()) return }
        // Parse before committing the marker. Corrupt legacy data must never be silently discarded.
        val old = try { JSONObject(legacy.openRead().bufferedReader().use { it.readText() }) }
            catch (e: java.io.FileNotFoundException) { if (legacy.baseFile.exists()) throw e else null }
        if (old != null) require(old.getInt("schema") == 1) { "生词本版本暂不支持，原数据已保留。" }
        db.beginTransaction()
        try {
            for (section in listOf("recent", "saved")) {
                val records = old?.getJSONArray(section) ?: JSONArray()
                for (i in records.length() - 1 downTo 0) {
                    val record = WordRecord.read(records.getJSONObject(i))
                    val previous = find(db, record.identity)
                    val newest = if (previous != null && previous.viewedAt > record.viewedAt) previous else record
                    put(db, newest)
                    db.execSQL("UPDATE entries SET $section=1 WHERE identity=?", arrayOf(record.identity))
                }
            }
            db.execSQL("INSERT INTO metadata VALUES ('legacy-imported', '1')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun find(db: SQLiteDatabase, identity: String): WordRecord? = db.rawQuery("SELECT payload FROM entries WHERE identity=?", arrayOf(identity)).use {
        if (it.moveToFirst()) WordRecord.read(JSONObject(it.getString(0))) else null
    }
    private fun put(db: SQLiteDatabase, record: WordRecord) {
        val values = ContentValues().apply {
            put("identity", record.identity); put("id", record.id); put("cache_key", record.cacheKey)
            put("viewed", record.viewedAt); put("payload", record.json().toString())
        }
        if (db.update("entries", values, "identity=?", arrayOf(record.identity)) == 0) db.insertOrThrow("entries", null, values)
    }
    private fun list(db: SQLiteDatabase, field: String): List<WordRecord> = db.rawQuery("SELECT payload FROM entries WHERE $field=1 ORDER BY viewed DESC, identity", null).use {
        buildList { while (it.moveToNext()) add(WordRecord.read(JSONObject(it.getString(0)))) }
    }
    private fun snapshot(db: SQLiteDatabase) = NotebookSnapshot(list(db, "saved"), list(db, "recent"))
    fun snapshot(): NotebookSnapshot = database { snapshot(it) }
    fun findResponse(cacheKey: String): String? = database { db ->
        db.rawQuery("SELECT payload FROM entries WHERE cache_key=? ORDER BY viewed DESC LIMIT 1", arrayOf(cacheKey)).use {
            if (it.moveToFirst()) WordRecord.read(JSONObject(it.getString(0))).response else null
        }
    }
    fun isSaved(id: String): Boolean = database { db -> db.rawQuery("SELECT 1 FROM entries WHERE (id=? OR identity=?) AND saved=1", arrayOf(id, id)).use { it.moveToFirst() } }
    fun recordRecent(record: WordRecord) = database { db ->
        db.beginTransaction()
        try {
            val old = find(db, record.identity)
            put(db, record.copy(lookupCount = (old?.lookupCount ?: 0) + 1))
            db.execSQL("UPDATE entries SET recent=1 WHERE identity=?", arrayOf(record.identity))
            db.execSQL("UPDATE entries SET recent=0 WHERE identity IN (SELECT identity FROM entries WHERE recent=1 ORDER BY viewed DESC, identity LIMIT -1 OFFSET $HISTORY_LIMIT)")
            db.execSQL("DELETE FROM entries WHERE saved=0 AND recent=0")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun save(record: WordRecord) = database { db ->
        put(db, find(db, record.identity) ?: record)
        db.execSQL("UPDATE entries SET saved=1 WHERE identity=?", arrayOf(record.identity))
    }
    fun remove(id: String) = database { db ->
        db.execSQL("UPDATE entries SET saved=0 WHERE id=? OR identity=?", arrayOf(id, id))
        db.execSQL("DELETE FROM entries WHERE saved=0 AND recent=0")
    }
    fun replace(previous: WordRecord, updated: WordRecord): NotebookSnapshot = database { db ->
        require(previous.query == updated.query && previous.origin == updated.origin)
        find(db, previous.identity)?.let { old -> put(db, updated.copy(viewedAt = old.viewedAt, lookupCount = old.lookupCount)) }
        snapshot(db)
    }
    fun export(): String = database { db ->
        JSONObject().put("format", "englishreader.vocabulary.v1").put("words", JSONArray().apply { list(db, "saved").forEach { put(it.json()) } }).toString(2)
    }
    companion object { const val HISTORY_LIMIT = 5000; private val lock = Any() }
}
