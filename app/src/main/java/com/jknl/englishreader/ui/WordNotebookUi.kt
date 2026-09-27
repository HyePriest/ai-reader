package com.jknl.englishreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jknl.englishreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

@Composable
internal fun WordNotebookDialog(notebook: WordNotebook? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { notebook ?: WordNotebook(context) }
    val repository = remember { WordRepository(context.applicationContext, notebook = store) }
    val config = remember { DeepSeekSettings(context) }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(NotebookSnapshot()) }
    var search by rememberSaveable { mutableStateOf("") }
    var recent by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<WordRecord?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    fun refresh(record: WordRecord) {
        if (refreshing) return
        refreshing = true
        refreshJob = scope.launch {
            try {
                val updated = requireNotNull(repository.lookup(record.query, record.origin,
                    forceRefresh = true, recordHistory = false).record)
                data = withContext(Dispatchers.IO) { store.replace(record, updated) }
                if (selected?.id == record.id) selected = updated
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "原记录已保留。${e.message ?: "更新失败，请重试。"}" }
            finally { refreshing = false }
        }
    }
    LaunchedEffect(Unit) {
        try { data = withContext(Dispatchers.IO) { store.snapshot() } }
        catch (_: Exception) { error = "生词本暂时无法读取，原数据已保留。" }
        finally { loading = false }
    }
    fun changeRecord(remove: Boolean) {
        val record = selected ?: return
        scope.launch {
            saving = true
            try {
                data = withContext(Dispatchers.IO) { if (remove) store.remove(record.id) else store.save(record); store.snapshot() }
                removing = false
            } catch (_: Exception) { message = "保存失败，原数据已保留。" } finally { saving = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = store.export()
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                }
                message = "已导出生词、全部释义、原句与来源。"
            } catch (_: Exception) { message = "导出失败，请重新选择保存位置。" }
        }
    }
    Dialog(onDismissRequest = { if (selected != null) selected = null else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("word-notebook"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (selected != null) GlyphButton("back", "返回词条列表", { selected = null })
                    Text(selected?.query?.word ?: "生词本", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected == null) TextButton(enabled = !loading && error == null && data.saved.isNotEmpty(), onClick = { export.launch("ai-reader-vocabulary.json") }) { Text("导出") }
                    GlyphButton("close", "关闭生词本", onDismiss)
                }
                if (selected == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = !recent, onClick = { recent = false }, label = { Text("生词本 ${data.saved.map { it.lemmaKey }.distinct().size}") })
                        FilterChip(selected = recent, onClick = { recent = true }, label = { Text("查词记录 ${data.recent.size}") })
                    }
                    Text(if (recent) "最多保留 5,000 条语境记录。同一处重复查询合并，离线可读。" else "按单词归并，释义在前，原句与出处在后。收藏独立保留。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(search, { search = it }, label = { Text("搜索单词、释义或原句") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val records = remember(data, recent, search) {
                        (if (recent) data.recent else data.saved).filter { r -> search.isBlank() ||
                            r.explanation.lemma.contains(search, true) || r.query.sentence.contains(search, true) ||
                            r.explanation.contextMeaning.contains(search, true) }
                    }
                    val groups = if (recent) records.map { listOf(it) } else records.groupBy { it.lemmaKey }.values.toList()
                    when {
                        loading -> CircularProgressIndicator()
                        error != null -> Text(error!!)
                        records.isEmpty() -> Text(if (recent) "还没有查词记录。成功查词后自动保留在这里。" else "长按查词后，点面板顶部「加入生词本」。", modifier = Modifier.padding(top = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(groups, key = { it.first().id }) { group ->
                                val record = group.first()
                                ListItem(headlineContent = { Text(record.explanation.lemma, fontFamily = FontFamily.Serif, fontSize = 21.sp) },
                                    supportingContent = { Column {
                                        Text(group.map { it.explanation.contextMeaning }.distinct().joinToString("；"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (!recent) Text("${group.size} 处原句", fontSize = 11.sp)
                                        Text("${record.goal.title} · ${StudyLevels.display(record.explanation.wordAdvice.decision)} · ${record.origin.bookTitle}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    } }, modifier = Modifier.clickable { selected = record })
                            }
                        }
                    }
                } else {
                    val record = selected!!
                    val saved = data.saved.any { it.id == record.id }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${record.goal.title} · 已保存的释义", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (saved) TextButton(enabled = !saving && !refreshing, onClick = { removing = true }) { Text("移出生词本") }
                        else Button(enabled = !saving && !refreshing, onClick = { changeRecord(false) }) { Text("加入生词本") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("本条来自 ${record.model} · ${record.goal.title}${if (record.promptVersion != WordPrompt.VERSION) " · 历史分析" else ""}",
                            fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !refreshing && !saving, onClick = { refresh(record) }) {
                            Text("重新分析 · ${config.model}")
                        }
                        Text("点击后将本条单词和原句发送给 DeepSeek，按当前「${config.goal.title}」目标更新，会产生 API 用量。", fontSize = 11.sp, lineHeight = 18.sp)
                        Text("主要释义", fontWeight = FontWeight.SemiBold)
                        val contexts = if (recent) listOf(record) else data.saved.filter { it.lemmaKey == record.lemmaKey }.ifEmpty { listOf(record) }
                        contexts.flatMap { it.explanation.senses }.distinctBy { it.pos to it.meaning }.forEach { sense ->
                            Text("${sense.pos} · ${sense.meaning}", fontSize = 16.sp, lineHeight = 25.sp)
                            if (sense.usage.isNotBlank()) Text(sense.usage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Text("这个词 · ${StudyLevels.display(record.explanation.wordAdvice.decision)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(record.explanation.wordAdvice.reason, fontSize = 14.sp, lineHeight = 22.sp)
                        Text("遇到这个词的原句", fontWeight = FontWeight.SemiBold)
                        contexts.forEach { item ->
                            Text(item.query.sentence, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 26.sp)
                            Text("此处：${item.explanation.contextMeaning}", fontWeight = FontWeight.SemiBold)
                            Text(item.explanation.contextExplanation, fontSize = 14.sp, lineHeight = 22.sp)
                            Text("此义 · ${StudyLevels.display(item.explanation.senseAdvice.decision)}：${item.explanation.senseAdvice.reason}", fontSize = 13.sp, lineHeight = 21.sp)
                            if (item.explanation.phrase.isNotBlank()) Text(item.explanation.phrase, fontSize = 13.sp)
                            Text("${item.origin.bookTitle}\n${item.origin.chapterTitle}", fontSize = 12.sp, lineHeight = 20.sp)
                            Text("查过 ${item.lookupCount} 次", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (contexts.size > 1) TextButton(enabled = !refreshing && !saving, onClick = { selected = item }) { Text(if (item.identity == record.identity) "当前管理的语境" else "管理此处释义与收藏") }
                            HorizontalDivider()
                        }
                    }
                }
                if (refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("正在重新分析…", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refreshJob?.cancel() }) { Text("停止") }
                    }
                }
            }
        }
        if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("移出生词本？") }, text = { Text("移除这条收藏，不会删除书籍。") },
            confirmButton = { TextButton(enabled = !saving, onClick = { changeRecord(true) }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } })
        if (message != null) AlertDialog(onDismissRequest = { message = null }, text = { Text(message!!) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } })
    }
}


