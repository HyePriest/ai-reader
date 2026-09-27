@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jknl.englishreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ReadingQuestionSheet(selection: ReadingQuestion, onDismiss: () -> Unit, onReselect: () -> Unit,
    onAppend: () -> Unit, ask: (suspend (ReadingQuestion, List<ReadingExchange>, String) -> String)? = null) {
    val context = LocalContext.current
    val assistant = remember { ReadingAssistant(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<ReadingExchange>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    fun send() {
        if (busy || question.isBlank()) return
        val text = question.trim()
        busy = true; error = null
        job = scope.launch {
            try {
                val answer = if (ask != null) ask(selection, history, text) else assistant.ask(selection, history, text)
                history = history + ReadingExchange(text, answer); question = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "提问未完成，请重试。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(history.size, busy, error) { scroll.animateScrollTo(scroll.maxValue) }
    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("reading-question-sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("问 AI", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onReselect) { Text("重选") }
                TextButton(enabled = !busy, onClick = onAppend) { Text("追加选文") }
                GlyphButton("close", "关闭提问", { job?.cancel(); onDismiss() })
            }
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selection.location, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp)) {
                        SelectionContainer { Text(if (showOriginal) selection.text else selection.text.take(240) + if (selection.text.length > 240) "…" else "", fontSize = 15.sp, lineHeight = 24.sp) }
                        if (selection.text.length > 240) TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "收起选文" else "展开全部选文") }
                    }
                }
                if (history.isEmpty()) {
                    Text("发送时附带上文，不附带后续正文。AI 解读可能有误，可继续追问依据。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val prompts = listOf("解释这段话", "这里的言外之意是什么？", "我的理解是：") +
                            if (!DocumentImporter.chinese(selection.text)) listOf("分析句子结构") else listOf("分析这段的措辞和表达")
                        prompts.forEach { prompt -> SuggestionChip(onClick = { question = prompt }, label = { Text(prompt, fontSize = 12.sp) }) }
                    }
                }
                history.forEach { turn ->
                    Text(turn.question, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(turn.answer, fontSize = 16.sp, lineHeight = 27.sp) }
                    HorizontalDivider()
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在阅读选文…", fontSize = 12.sp) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            OutlinedTextField(question, { if (it.length <= 2000) question = it }, modifier = Modifier.fillMaxWidth().testTag("reading-question-input"),
                placeholder = { Text(if (history.isEmpty()) "我理解这里是……，对吗？" else "继续追问…") }, minLines = 2, maxLines = 4, enabled = !busy)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (busy) TextButton(onClick = { job?.cancel() }) { Text("停止") }
                Button(enabled = !busy && question.isNotBlank(), onClick = ::send, modifier = Modifier.testTag("send-reading-question")) { Text(if (error != null) "重试" else "发送") }
            }
        }
    }
}
