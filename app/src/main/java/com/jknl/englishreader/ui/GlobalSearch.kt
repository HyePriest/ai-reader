package com.jknl.englishreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.theme.ReadingSerif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ArticleHit(val article: ShelfBook, val inTitle: Boolean, val count: Int, val snippet: String)
internal data class ThreadHit(val article: ShelfBook, val thread: ChatThread, val snippet: String)

/** Pure ranking used by the search screen: title matches first, then the most matches in the text. */
internal object LibrarySearch {
    fun article(article: ShelfBook, book: Book?, query: String): ArticleHit? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val inTitle = listOf(article.title, article.titleZh, article.subtitle).any { it.contains(q, true) }
        val body = book?.chapters?.flatMap { c -> c.paragraphs.filterNot { it.heading }.map { it.text } }.orEmpty()
        var count = 0; var first: String? = null
        body.forEach { text ->
            var at = text.indexOf(q, 0, true)
            while (at >= 0) { if (first == null) first = ChatStore.snippet(text, q, 45); count++; at = text.indexOf(q, at + q.length, true) }
        }
        if (!inTitle && count == 0) return null
        return ArticleHit(article, inTitle, count, first ?: article.subtitle)
    }
    fun rank(hits: List<ArticleHit>) = hits.sortedWith(compareByDescending<ArticleHit> { it.inTitle }.thenByDescending { it.count })
}

@Composable
internal fun GlobalSearchScreen(articles: List<ShelfBook>, store: LibraryStore, onBack: () -> Unit,
    onOpen: (ShelfBook, String?) -> Unit, onOpenThread: (ShelfBook, ChatThread) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var articleHits by remember { mutableStateOf<List<ArticleHit>?>(emptyList()) }
    var threadHits by remember { mutableStateOf<List<ThreadHit>?>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query, articles) {
        val q = query.trim()
        if (q.isEmpty()) { articleHits = emptyList(); threadHits = emptyList(); return@LaunchedEffect }
        articleHits = null; threadHits = null
        delay(300)
        articleHits = withContext(Dispatchers.IO) {
            LibrarySearch.rank(articles.mapNotNull { a ->
                ensureActive()
                LibrarySearch.article(a, runCatching { store.open(a.id) }.getOrNull(), q)
            }).take(80)
        }
        threadHits = withContext(Dispatchers.IO) {
            val chats = ChatStore(context)
            val byId = articles.associateBy { it.id }
            chats.books().mapNotNull { id -> byId[id] }.flatMap { a ->
                ensureActive()
                ChatStore.search(chats.threads(a.id), q).map { (t, i) ->
                    val e = t.exchanges.getOrNull(i)
                    val source = when { e == null -> t.text; e.question.contains(q, true) -> e.question; else -> e.answer }
                    ThreadHit(a, t, ChatStore.snippet(source, q, 45))
                }
            }.sortedByDescending { it.thread.updatedAt }.take(80)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("global-search")) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("back", "返回", onBack)
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜文章标题、正文或问 AI 的记录") },
                shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).focusRequester(focus).testTag("global-search-input"))
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("文章" + (articleHits?.let { if (query.isBlank()) "" else " ${it.size}" } ?: "")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("问 AI 记录" + (threadHits?.let { if (query.isBlank()) "" else " ${it.size}" } ?: "")) })
        }
        val loading = if (tab == 0) articleHits == null else threadHits == null
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                query.isBlank() -> item { Hint("输入英文或中文关键词。文章会搜标题、中文标题和正文；点正文结果会直接跳到原文并高亮。") }
                loading -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Hint("  正在搜索…") } }
                tab == 0 && articleHits.orEmpty().isEmpty() -> item { Hint("没有找到包含「${query.trim()}」的文章。") }
                tab == 1 && threadHits.orEmpty().isEmpty() -> item { Hint("问 AI 的记录里没有「${query.trim()}」。") }
                tab == 0 -> items(articleHits.orEmpty(), key = { it.article.id }) { hit ->
                    ResultCard(onClick = { onOpen(hit.article, if (hit.count > 0) query.trim() else null) }) {
                        Text(hit.article.title, fontFamily = ReadingSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (hit.article.titleZh.isNotBlank()) Text(hit.article.titleZh, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        if (hit.snippet.isNotBlank()) Highlighted(hit.snippet, query)
                        Text(listOfNotNull(hit.article.collection.ifBlank { null }, if (hit.count > 0) "正文 ${hit.count} 处" else "标题匹配").joinToString(" · "),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> items(threadHits.orEmpty(), key = { it.article.id + it.thread.id }) { hit ->
                    ResultCard(onClick = { onOpenThread(hit.article, hit.thread) }) {
                        Text(if (hit.thread.whole) "全文提问" else hit.thread.text.replace('\n', ' '), fontFamily = if (hit.thread.whole) null else ReadingSerif,
                            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Highlighted(hit.snippet, query)
                        Text(hit.article.titleZh.ifBlank { hit.article.title }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ResultCard(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun Highlighted(text: String, query: String) {
    val q = query.trim()
    val color = MaterialTheme.colorScheme.tertiaryContainer
    Text(buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val at = if (q.isEmpty()) -1 else text.indexOf(q, i, true)
            if (at < 0) { append(text.substring(i)); break }
            append(text.substring(i, at))
            withStyle(SpanStyle(background = color)) { append(text.substring(at, at + q.length)) }
            i = at + q.length
        }
    }, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
}
