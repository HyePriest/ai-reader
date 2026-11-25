@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.jknl.englishreader.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.theme.ReadingSerif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prompt chips the reader can edit; separate lists for whole-article, passage-in-article and book questions. */
internal class PromptSettings(context: Context) {
    private val prefs = context.getSharedPreferences("prompts", Context.MODE_PRIVATE)
    fun get(kind: String): List<String> = prefs.getString(kind, null)?.split('\n')?.filter { it.isNotBlank() } ?: ArticlePrompts.defaults(kind)
    fun set(kind: String, prompts: List<String>) { prefs.edit().putString(kind, prompts.joinToString("\n")).apply() }
    fun reset(kind: String) { prefs.edit().remove(kind).apply() }
}

internal object ArticlePrompts {
    val whole = listOf("全文大意是什么？", "逐段概括段落大意", "作者的观点和态度是什么？", "挑出最难的 3 个长难句并拆解", "按考研阅读出 5 道选择题", "这篇文章值得积累哪些词组？")
    val passage = listOf("翻译这段并标出生词", "拆解句子结构", "标出这段的生词", "这段在全文中起什么作用？", "解释这段话")
    val book = listOf("解释这段话", "这里的言外之意是什么？", "分析句子结构", "标出这段的生词", "我的理解是：")
    fun defaults(kind: String) = when (kind) { "whole" -> whole; "passage" -> passage; else -> book }
    fun kind(q: ReadingQuestion) = when { q.wholeArticle -> "whole"; q.article.isNotBlank() -> "passage"; else -> "book" }
}

/** Conversation in a bottom sheet that does not drag, so scrolling never moves it. Closing never cancels a request. */
@Composable
internal fun ReadingQuestionScreen(session: ChatSession, initialQuestion: String?, onClose: () -> Unit, onReselect: () -> Unit,
    onAppend: () -> Unit, onOpenThread: (ChatThread) -> Unit) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(session) { if (!initialQuestion.isNullOrBlank()) ChatSessions.send(context, session, initialQuestion) }
    DisposableEffect(session) { session.visible = true; session.unseen = false; onDispose { ChatSessions.close(session) } }
    var question by remember(session) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetGesturesEnabled = false,
        dragHandle = null, containerColor = MaterialTheme.colorScheme.background) {
    ReadingQuestionContent(session.question, session.thread, session.pending, session.error, session.highlight, question, { if (it.length <= 2000) question = it },
        onSend = { text -> ChatSessions.send(context, session, text); if (text == question.trim()) question = "" },
        onStop = { ChatSessions.stop(session) }, onClear = { ChatSessions.clear(context, session) },
        onUseArticle = { ChatSessions.setUseArticle(context, session, it) },
        onReselect = onReselect, onAppend = onAppend, onClose = onClose, onOpenThread = onOpenThread)
    }
}

/** The conversation itself, separate from session state so it can be previewed and tested. */
@Composable
internal fun ReadingQuestionContent(selection: ReadingQuestion, thread: ChatThread, pending: String?, error: String?, highlight: Int,
    question: String, onQuestion: (String) -> Unit, onSend: (String) -> Unit, onStop: () -> Unit, onClear: () -> Unit,
    onUseArticle: (Boolean) -> Unit, onReselect: () -> Unit, onAppend: () -> Unit, onClose: () -> Unit, onOpenThread: (ChatThread) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val promptSettings = remember { PromptSettings(context) }
    val kind = ArticlePrompts.kind(selection)
    var prompts by remember(kind) { mutableStateOf(promptSettings.get(kind)) }
    var editing by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val history = thread.exchanges
    val busy = pending != null
    val article = selection.article.isNotBlank()
    val list = rememberLazyListState()
    // Header items before the first exchange: quote and prompts.
    val lead = 2
    LaunchedEffect(history.size, pending) { if (history.isNotEmpty() || pending != null) list.animateScrollToItem(lead + history.size + if (pending != null) 1 else 0) }
    LaunchedEffect(highlight) { if (highlight >= 0) list.animateScrollToItem(lead + highlight) }
    Surface(Modifier.fillMaxWidth().testTag("reading-question-sheet"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding()) {
            Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (selection.wholeArticle) "问 AI · 全文" else "问 AI · 选文", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(selection.location, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    GlyphButton("more", "更多", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        if (article && !selection.wholeArticle) {
                            MenuRow("doc", "参考全文", enabled = !busy, checked = selection.useArticle) { onUseArticle(!selection.useArticle) }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (!selection.wholeArticle) {
                            MenuRow("select", "重新选文", enabled = !busy) { menu = false; onReselect() }
                            MenuRow("plus", "追加选文", enabled = !busy) { menu = false; onAppend() }
                        }
                        MenuRow("clock", "问答记录") { menu = false; historyOpen = true }
                        MenuRow("edit", "常用问题") { menu = false; editing = true }
                        if (history.isNotEmpty()) MenuRow("trash", "清空对话", enabled = !busy, danger = true) { menu = false; onClear() }
                    }
                }
                GlyphButton("close", "关闭", onClose)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "quote") { if (!selection.wholeArticle) QuoteCard(selection.text) }
                item(key = "prompts") {
                    if (history.isEmpty() && pending == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        prompts.forEach { prompt -> PromptChip(prompt) { if (prompt.endsWith("：")) onQuestion(prompt) else onSend(prompt) } }
                    }
                }
                itemsIndexed(history, key = { i, _ -> "turn-$i" }) { i, turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        UserBubble(turn.question)
                        AnswerBlock(turn.answer)
                    }
                }
                if (pending != null) item(key = "pending") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        UserBubble(pending)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AiBadge()
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(if (selection.sendsArticle) "正在读全文…" else "正在思考…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (error != null) item(key = "error") {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Composer(question, onQuestion, busy, history.isEmpty(), onSend = { onSend(question.trim()) }, onStop = onStop)
        }
    }
    if (editing) PromptEditor(prompts, onDismiss = { editing = false }, onSave = { promptSettings.set(kind, it); prompts = it; editing = false },
        onReset = { promptSettings.reset(kind); prompts = promptSettings.get(kind); editing = false })
    if (historyOpen) ThreadHistory(thread.bookId, onDismiss = { historyOpen = false }, onOpen = { historyOpen = false; onOpenThread(it) })
}

@Composable
private fun PromptChip(text: String, small: Boolean = false, subtle: Boolean = false, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = if (subtle) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (subtle) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier.clickable(onClick = onClick)) {
        Text(text, fontSize = if (small) 12.sp else 13.sp, color = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 7.dp else 9.dp))
    }
}

@Composable
private fun QuoteCard(text: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(enabled = text.length > 220) { open = !open }) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        SelectionContainer {
            Text(if (open || text.length <= 220) text else text.take(220) + "…", fontFamily = ReadingSerif, fontSize = 15.sp, lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(.86f).wrapContentWidth(Alignment.End)) {
            Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun AiBadge() {
    Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Text("AI", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun AnswerBlock(answer: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AiBadge()
        SelectionContainer { MarkdownText(answer, Modifier.fillMaxWidth().testTag("ai-answer")) }
        IconButton(onClick = { clipboard.setText(AnnotatedString(answer)); copied = true }, modifier = Modifier.size(32.dp).offset(x = (-6).dp).testTag("copy-answer")) {
            SmallGlyph(if (copied) "check" else "copy", if (copied) "已复制" else "复制回答")
        }
    }
}

/** A menu row with an icon, so each action is recognisable at a glance. */
@Composable
internal fun MenuRow(icon: String, label: String, enabled: Boolean = true, checked: Boolean? = null, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontSize = 15.sp, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) }, enabled = enabled, onClick = onClick,
        leadingIcon = { SmallGlyph(icon, label) },
        trailingIcon = if (checked == null) null else { { Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(.75f).testTag("use-article")) } },
        modifier = Modifier.widthIn(min = 200.dp))
}

@Composable
private fun Composer(value: String, onChange: (String) -> Unit, busy: Boolean, first: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                TextField(value, onChange, modifier = Modifier.fillMaxWidth().testTag("reading-question-input"), maxLines = 5,
                    placeholder = { Text(if (first) "提问" else "继续追问", fontSize = 14.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
            }
            Spacer(Modifier.width(8.dp))
            if (busy) FilledTonalButton(onClick = onStop, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("■", fontSize = 14.sp) }
            else Button(onClick = onSend, enabled = value.isNotBlank(), shape = CircleShape, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp).testTag("send-reading-question")) { Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PromptEditor(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit, onReset: () -> Unit) {
    var items by remember { mutableStateOf(initial) }
    var draft by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("常用问题") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (i > 0) TextButton(onClick = { items = items.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) } }, contentPadding = PaddingValues(4.dp)) { Text("上移", fontSize = 12.sp) }
                    IconButton(onClick = { items = items - p }, modifier = Modifier.size(36.dp)) { SmallGlyph("close", "删除") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { if (it.length <= 200) draft = it }, singleLine = true, modifier = Modifier.weight(1f).testTag("new-prompt"),
                    placeholder = { Text("新的常用问题") })
                TextButton(enabled = draft.isNotBlank(), onClick = { items = items + draft.trim(); draft = "" }) { Text("添加") }
            }
            TextButton(onClick = onReset) { Text("恢复默认") }
        }
    }, confirmButton = { TextButton(onClick = { onSave((items + listOfNotNull(draft.trim().takeIf { it.isNotEmpty() })).distinct()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Saved conversations of this article; tapping one reopens it. */
@Composable
private fun ThreadHistory(bookId: String, onDismiss: () -> Unit, onOpen: (ChatThread) -> Unit) {
    val context = LocalContext.current.applicationContext
    val threads by produceState<List<ChatThread>?>(null, bookId) {
        value = withContext(Dispatchers.IO) { runCatching { ChatStore(context).threads(bookId) }.getOrDefault(emptyList()) }.sortedByDescending { it.updatedAt }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("问答记录") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val list = threads
            when {
                list == null -> CircularProgressIndicator(Modifier.size(20.dp))
                list.isEmpty() -> Text("还没有保存的问答。")
                else -> list.forEach { t ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(vertical = 8.dp)) {
                        Text(if (t.whole) "全文" else t.text.replace('\n', ' '), fontFamily = if (t.whole) null else ReadingSerif, fontSize = 14.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (t.whole) FontWeight.SemiBold else FontWeight.Normal)
                        Text("${t.exchanges.size} 个问题 · ${t.exchanges.lastOrNull()?.question.orEmpty()}", fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
