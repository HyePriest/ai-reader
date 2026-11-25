package com.jknl.englishreader.data

import android.content.Context
import java.time.LocalDate

/** A monotonic interval accumulator. Each millisecond is handed to persistence exactly once. */
class ReadingTimer(private val now: () -> Long) {
    private var started: Long? = null
    fun setRunning(running: Boolean): Long {
        val delta = checkpoint()
        started = if (running) now() else null
        return delta
    }
    fun checkpoint(): Long {
        val start = started ?: return 0
        val end = now()
        started = end
        return (end - start).coerceAtLeast(0)
    }
}

class ReadingTime(context: Context) {
    private val prefs = context.getSharedPreferences("reading-time", Context.MODE_PRIVATE)
    fun total(id: String) = prefs.getLong("$id.total", 0L)
    fun today(id: String) = prefs.getLong("$id.${LocalDate.now()}", 0L)
    fun add(id: String, millis: Long) {
        if (millis <= 0) return
        synchronized(lock) {
            val day = "$id.${LocalDate.now()}"
            prefs.edit().putLong("$id.total", total(id) + millis)
                .putLong(day, prefs.getLong(day, 0L) + millis).apply()
        }
    }
    companion object { private val lock = Any() }
}

fun readingDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "${seconds} 秒"
        seconds < 3600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3600} 小时 ${seconds / 60 % 60} 分钟"
    }
}

/** When each article or book was last opened, and which are starred. Kept on this device only. */
class ReadingMarks(context: Context) {
    private val prefs = context.getSharedPreferences("reading-marks", Context.MODE_PRIVATE)
    fun opened(id: String) = prefs.getLong("opened.$id", 0L)
    fun markOpened(id: String, at: Long = System.currentTimeMillis()) { prefs.edit().putLong("opened.$id", at).apply() }
    fun favorite(id: String) = prefs.getBoolean("star.$id", false)
    fun setFavorite(id: String, value: Boolean) { prefs.edit().apply { if (value) putBoolean("star.$id", true) else remove("star.$id") }.apply() }
    fun forget(id: String) { prefs.edit().remove("opened.$id").remove("star.$id").apply() }
}
