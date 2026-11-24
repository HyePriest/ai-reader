@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jknl.englishreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ReadingQuestionSheet(selection: ReadingQuestion, onDismiss: () -> Unit, onReselect: () -> Unit,
    onAppend: () -> Unit, ask: (suspend (ReadingQuestion, List<ReadingExchange>, String) -> String)? = null,
    initialHistory: List<ReadingExchange> = emptyList(), onHistory: (List<ReadingExchange>) -> Unit = {}, initialQuestion: String? = null) {
    val context = LocalContext.current
    val assistant = remember { ReadingAssistant(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(initialHistory) }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = pending != null
    val scroll = rememberScrollState()
    fun send(text: String = question.trim()) {
        if (busy || text.isBlank()) return
        pending = text; error = null; question = ""
        job = scope.launch {
            try {
                val answer = if (ask != null) ask(selection, history, text) else assistant.ask(selection, history, text)
                history = history + ReadingExchange(text, answer)
                onHistory(history)
            } catch (e: CancellationException) { question = text; throw e }
            catch (e: Exception) { error = e.message ?: "提问未完成，请重试。"; question = text }
            finally { pending = null }
        }
    }
    LaunchedEffect(history.size, pending, error) { scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(Unit) { if (!initialQuestion.isNullOrBlank()) send(initialQuestion) }
    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("reading-question-sheet")) {
        ReadingQuestionContent(selection, history, pending, error, question, { if (it.length <= 2000) question = it }, scroll,
            onSend = { send(it) }, onStop = { job?.cancel() }, onClear = { history = emptyList(); onHistory(history) },
            onReselect = onReselect, onAppend = onAppend, onClose = { job?.cancel(); onDismiss() })
    }
}

/** The conversation itself, separate from the sheet so it can be previewed and tested. */
@Composable
internal fun ReadingQuestionContent(selection: ReadingQuestion, history: List<ReadingExchange>, pending: String?, error: String?,
    question: String, onQuestion: (String) -> Unit, scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
    onSend: (String) -> Unit, onStop: () -> Unit, onClear: () -> Unit, onReselect: () -> Unit, onAppend: () -> Unit, onClose: () -> Unit) {
    val busy = pending != null
    val article = selection.article.isNotBlank()
    val prompts = when {
        selection.wholeArticle -> ArticlePrompts.whole
        article -> ArticlePrompts.passage
        else -> listOf("解释这段话", "这里的言外之意是什么？", "我的理解是：") +
            if (!DocumentImporter.chinese(selection.text)) listOf("分析句子结构") else listOf("分析这段的措辞和表达")
    }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (selection.wholeArticle) "问 AI · 全文" else "问 AI · 选文", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(selection.location, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selection.wholeArticle) {
                    if (history.isNotEmpty()) TextButton(enabled = !busy, onClick = onClear) { Text("清空") }
                } else {
                    TextButton(enabled = !busy, onClick = onReselect) { Text("重选") }
                    TextButton(enabled = !busy, onClick = onAppend) { Text("追加") }
                }
                GlyphButton("close", "关闭", onClose)
            }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(4.dp))
                if (!selection.wholeArticle) QuoteCard(selection.text)
                if (history.isEmpty() && pending == null) {
                    Text(if (article) "AI 会读整篇文章再回答" + if (selection.wholeArticle) "，对话随文章保存。" else "。"
                        else "发送时附带这段之前的上下文，不会带上后面的内容。", fontSize = 12.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        prompts.forEach { prompt ->
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.clickable { if (prompt.endsWith("：")) onQuestion(prompt) else onSend(prompt) }) {
                                Text(prompt, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                history.forEach { turn ->
                    UserBubble(turn.question)
                    AnswerBlock(turn.answer)
                }
                pending?.let {
                    UserBubble(it)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AiBadge()
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(if (article) "正在读文章…" else "正在读选文…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Composer(question, onQuestion, busy, history.isEmpty(), onSend = { onSend(question.trim()) }, onStop = onStop)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AiBadge()
        SelectionContainer { MarkdownText(answer, Modifier.fillMaxWidth().testTag("ai-answer")) }
        TextButton(onClick = { clipboard.setText(AnnotatedString(answer)); copied = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (copied) "已复制" else "复制", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(value: String, onChange: (String) -> Unit, busy: Boolean, first: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                TextField(value, onChange, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("reading-question-input"), maxLines = 5,
                    placeholder = { Text(if (first) "问点什么，比如：我理解这里是……，对吗？" else "继续追问…", fontSize = 14.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            }
            Spacer(Modifier.width(8.dp))
            if (busy) FilledTonalButton(onClick = onStop, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("■", fontSize = 14.sp) }
            else Button(onClick = onSend, enabled = value.isNotBlank(), shape = CircleShape, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp).testTag("send-reading-question")) { Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

internal object ArticlePrompts {
    val whole = listOf("全文大意是什么？", "逐段概括段落大意", "作者的观点和态度是什么？", "挑出最难的 3 个长难句并拆解", "按考研阅读出 5 道选择题", "这篇文章值得积累哪些词组？")
    val passage = listOf("翻译这段", "分析句子结构", "这段在全文中起什么作用？", "解释这段话")
}
