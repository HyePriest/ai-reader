package com.jknl.englishreader.ui

import android.content.Context
import androidx.compose.runtime.*
import com.jknl.englishreader.data.*
import kotlinx.coroutines.*

/** Live state of one conversation. It outlives the chat screen, so leaving never loses an answer. */
@Stable
internal class ChatSession(val book: Book, question: ReadingQuestion, thread: ChatThread) {
    var question by mutableStateOf(question)
    var thread by mutableStateOf(thread)
    var pending by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** Index of a cached answer the user just asked again, to scroll to it. */
    var highlight by mutableIntStateOf(-1)
    /** An answer arrived while the chat screen was closed. */
    var unseen by mutableStateOf(false)
    var visible = false
    internal var job: Job? = null
}

internal object ChatSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = mutableStateMapOf<String, ChatSession>()

    fun open(context: Context, book: Book, question: ReadingQuestion): ChatSession {
        val id = ChatThread.idFor(question.passages)
        val key = "${book.id}:$id"
        sessions[key]?.let { s -> s.visible = true; s.unseen = false; return s }
        val saved = runCatching { ChatStore(context.applicationContext).thread(book.id, id) }.getOrNull()
        val q = if (saved != null) question.copy(useArticle = saved.useArticle) else question
        return ChatSession(book, q, saved ?: ChatThread.from(book.id, q)).also { it.visible = true; sessions[key] = it }
    }

    /** Conversations of this book that are still answering or have an answer not yet seen. */
    fun active(bookId: String): List<ChatSession> = sessions.values.filter { it.book.id == bookId && (it.pending != null || it.unseen) }

    fun close(session: ChatSession) { session.visible = false }

    fun setUseArticle(context: Context, session: ChatSession, value: Boolean) {
        session.question = session.question.copy(useArticle = value)
        session.thread = session.thread.copy(useArticle = value)
        persist(context, session)
    }

    fun send(context: Context, session: ChatSession, text: String) {
        val question = text.trim()
        if (question.isEmpty() || session.pending != null) return
        // Same selection, same question: show the saved answer instead of paying for it again.
        val cached = session.thread.cached(question)
        if (cached >= 0) { session.highlight = cached; session.error = null; return }
        session.pending = question; session.error = null; session.highlight = -1
        val app = context.applicationContext
        session.job = scope.launch {
            try {
                val answer = ReadingAssistant(app).ask(session.question, session.thread.exchanges, question)
                session.thread = session.thread.copy(exchanges = session.thread.exchanges + ReadingExchange(question, answer),
                    updatedAt = System.currentTimeMillis(), text = session.question.text, location = session.question.location)
                persist(app, session)
                if (!session.visible) session.unseen = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.error = e.message ?: "提问未完成，请重试。" }
            finally { session.pending = null; session.job = null }
        }
    }

    fun stop(session: ChatSession) { session.job?.cancel() }

    fun clear(context: Context, session: ChatSession) {
        stop(session)
        session.thread = session.thread.copy(exchanges = emptyList())
        runCatching { ChatStore(context.applicationContext).delete(session.book.id, session.thread.id) }
    }

    private fun persist(context: Context, session: ChatSession) {
        val thread = session.thread
        scope.launch(Dispatchers.IO) { runCatching { ChatStore(context.applicationContext).save(thread) } }
    }
}
