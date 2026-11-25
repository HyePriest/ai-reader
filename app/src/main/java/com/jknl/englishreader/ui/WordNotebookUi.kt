package com.jknl.englishreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GlyphButton("back", if (selected != null) "返回词条列表" else "关闭生词本", { if (selected != null) selected = null else onDismiss() })
                    Text(if (selected != null) "" else "生词本", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    if (selected == null) TextButton(enabled = !loading && error == null && data.saved.isNotEmpty(), onClick = { export.launch("ai-reader-vocabulary.json") }) { Text("导出") }
                }
                if (selected == null) {
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !recent, onClick = { recent = false }, label = { Text("收藏 ${data.saved.map { it.lemmaKey }.distinct().size}") })
                        FilterChip(selected = recent, onClick = { recent = true }, label = { Text("查词记录 ${data.recent.size}") })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(search, { search = it }, placeholder = { Text("搜索单词、释义或原句") }, singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
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
                        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(groups, key = { it.first().id }) { group ->
                                val record = group.first()
                                Card(Modifier.fillMaxWidth().clickable { selected = record }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Text(record.explanation.lemma, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                            Text("  " + record.explanation.pos, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                                            StudyBadge(StudyLevels.display(record.explanation.wordAdvice.decision))
                                        }
                                        Text(group.map { it.explanation.contextMeaning }.distinct().joinToString("；"), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(if (!recent && group.size > 1) "${group.size} 处原句" else null, record.origin.bookTitle).joinToString(" · "),
                                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val record = selected!!
                    val saved = data.saved.any { it.id == record.id }
                    val contexts = if (recent) listOf(record) else data.saved.filter { it.lemmaKey == record.lemmaKey }.ifEmpty { listOf(record) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                            Text(record.explanation.lemma, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                            Text("  " + record.explanation.pos, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (saved) OutlinedButton(enabled = !saving && !refreshing, onClick = { removing = true }) { Text("移出收藏") }
                            else Button(enabled = !saving && !refreshing, onClick = { changeRecord(false) }) { Text("加入收藏") }
                            TextButton(enabled = !refreshing && !saving, onClick = { refresh(record) }) { Text("重新分析") }
                        }
                        NoteSection("主要释义") {
                            contexts.flatMap { it.explanation.senses }.distinctBy { it.pos to it.meaning }.forEach { sense ->
                                Row {
                                    Text(sense.pos, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp).padding(top = 2.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(sense.meaning, fontSize = 15.sp, lineHeight = 22.sp)
                                        if (sense.usage.isNotBlank()) Text(sense.usage, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        NoteSection("要不要背") {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { StudyBadge(StudyLevels.display(record.explanation.wordAdvice.decision)) }
                            Text(record.explanation.wordAdvice.reason, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                        NoteSection("遇到它的原句") {
                            contexts.forEach { item ->
                                Row(Modifier.height(IntrinsicSize.Min)) {
                                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item.query.sentence, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif, fontSize = 16.sp, lineHeight = 24.sp)
                                        Text("此处：${item.explanation.contextMeaning}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(item.explanation.contextExplanation, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (item.explanation.phrase.isNotBlank()) Text(item.explanation.phrase, fontSize = 13.sp)
                                        Text("${item.origin.bookTitle} · 查过 ${item.lookupCount} 次", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (contexts.size > 1 && item.identity != record.identity) TextButton(enabled = !refreshing && !saving, onClick = { selected = item },
                                            contentPadding = PaddingValues(0.dp)) { Text("管理这一处", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                        Text("${record.model} · ${record.goal.title}${if (record.promptVersion != WordPrompt.VERSION) " · 历史分析" else ""} · 重新分析会把单词和原句发给 DeepSeek",
                            fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun NoteSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun StudyBadge(label: String) {
    val strong = label.contains("需要")
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
