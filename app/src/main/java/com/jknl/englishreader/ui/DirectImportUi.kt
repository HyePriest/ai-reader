@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jknl.englishreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jknl.englishreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BookImportPreview(book: Book, onDismiss: () -> Unit, onSave: suspend (Book) -> Unit) {
    val sourceDirectory = LocalContext.current.filesDir
    var title by remember { mutableStateOf(book.title) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var reviewed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val guessed = book.chapters.any { c -> c.alignments.any { it.source == "交替双语（待核对）" } }
    val scope = rememberCoroutineScope()
    ImportDialog("确认导入书籍", saving, onDismiss) {
        OutlinedTextField(title, { title = it }, label = { Text("书名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("${book.chapters.size} 章 · ${book.countLabel} · ${book.chapters.sumOf { it.alignments.size }} 组中文", fontSize = 12.sp)
        if (guessed) Text("检测到中英交替段落。以下对应根据排列顺序生成，请核对；合译会保留为一组。导入后可点译文面板的编辑按钮修正。", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        ImportChapterPicker("预览章节", book.chapters.map { it.title }, chapterIndex, !saving) { chapterIndex = it }
        val chapter = book.chapters[chapterIndex]
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(chapter.paragraphs) { _, p ->
                if (p.pdfPage != null) PdfPageImage(book.id, p.pdfPage, sourceDirectory)
                Text(p.text)
                chapter.alignments.filter { it.ranges.firstOrNull()?.paragraph == p.id }.forEach { a ->
                    Text(a.chinese, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    if (a.source == "AI 补译") Text("AI 补译", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
                HorizontalDivider()
            }
        }
        if (guessed) Row { Checkbox(reviewed, { reviewed = it }); Text("已核对中英文排列和对应", Modifier.padding(top = 12.dp), fontSize = 13.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Button(enabled = !saving && title.isNotBlank() && (!guessed || reviewed), onClick = {
            scope.launch { saving = true; try { onSave(book.copy(title = title.trim())) } catch (e: Exception) { error = e.message } finally { saving = false } }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "保存中…" else "导入书架") }
    }
}

private data class ChineseSession(val book: Book, val document: ChineseDocument, val plans: List<ChapterPairing>, val matchingMessage: String?)

@Composable
internal fun DirectChineseImportActions(book: Book, chapterIndex: Int, onUpdate: suspend (Book) -> Unit) {
    val context = LocalContext.current
    val store = remember { LibraryStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ChineseSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val original = book
                val doc = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开文件" }.use { ChineseImport.read(it, store.fileName(uri)) }
                }
                val matched = withContext(Dispatchers.Default) { doc.bilingualSource?.let { runCatching { CompanionMatching.plans(original, doc) } } }
                session = ChineseSession(original, doc, matched?.getOrNull().orEmpty(), matched?.exceptionOrNull()?.message)
            } catch (e: Exception) { message = "读取失败：${e.message}" } finally { busy = false }
        }
    }
    Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "正在读取…" else "导入中文文件")
    }
    Text("直接选择有文字正文的中文 EPUB、PDF、MOBI / AZW3、TXT、FB2、DOCX、RTF 或双语书籍 JSON；在手机上配对，无须导出英文。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(enabled = !busy, onClick = { undo = true }) { Text("撤销最近一次中文修改", fontSize = 12.sp) }
    session?.let { s -> ChinesePairingDialog(s.book, s.document, chapterIndex, initialPlans = s.plans, matchingMessage = s.matchingMessage, onDismiss = { session = null }, onSave = { result ->
        require(book == s.book) { "书籍已变化，请重新导入中文" }
        onUpdate(result)
        session = null
        message = "中文已保存，点击英文即可查看。需要调整时可在译文面板编辑；本次修改可以撤销。"
    }) }
    if (undo) AlertDialog(onDismissRequest = { if (!busy) undo = false }, title = { Text("撤销最近一次中文修改？") },
        text = { Text("恢复上一次补译或编辑之前的书籍内容，保留当前阅读进度。只允许撤销最近一次修改。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try {
                val restored = withContext(Dispatchers.IO) { store.undoCandidate(book) }
                onUpdate(restored)
                withContext(Dispatchers.IO) { store.clearUndo(book) }
                undo = false; message = "已撤销，阅读进度保留。"
            } catch (e: Exception) { message = e.message; undo = false } finally { busy = false }
        } }) { Text("撤销") } }, dismissButton = { TextButton(enabled = !busy, onClick = { undo = false }) { Text("取消") } })
    message?.let { ImportMessage(it) { message = null } }
}

@Composable
internal fun ChinesePairingDialog(book: Book, document: ChineseDocument, initialChapter: Int, initialPlans: List<ChapterPairing> = emptyList(), matchingMessage: String? = null, onDismiss: () -> Unit, onSave: suspend (Book) -> Unit) {
    val scope = rememberCoroutineScope()
    var englishIndex by remember { mutableIntStateOf(initialPlans.firstOrNull()?.chapterIndex ?: initialChapter) }
    var chineseIndex by remember { mutableIntStateOf(initialPlans.firstOrNull()?.chineseIndex ?: initialChapter.coerceAtMost(document.chapters.lastIndex)) }
    var sentences by remember { mutableStateOf(true) }
    var clauses by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(initialPlans.associateBy { it.chapterIndex }) }
    var replace by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<TranslationImport?>(null) }
    var exactSource by remember { mutableStateOf(initialPlans.isNotEmpty()) }
    fun change(plan: ChapterPairing) { plans = plans + (plan.chapterIndex to plan); reviewed = false; error = null; exactSource = false }
    val current = plans[englishIndex]
    val selectedCount = plans.values.sumOf { p -> p.pairs.count { it.selected } }
    ImportDialog("为《${book.title}》导入中文", busy, onDismiss) {
        Text("中文：${document.title}", fontSize = 12.sp, maxLines = 2)
        Text(if (exactSource) "已用文件中的英文原文唯一定位，无须按长度猜配。请检查译文和保存范围，已有中文默认保留。"
            else matchingMessage?.let { "英文直接定位未通过：$it 可选择章节人工配对。" }
                ?: "候选按顺序与长度生成，不能判断译意。请检查章节、正文开头和结尾；错配可修改范围或取消。", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { ImportChapterPicker("英文", book.chapters.map { it.title }, englishIndex, !busy) {
                englishIndex = it; chineseIndex = plans[it]?.chineseIndex ?: it.coerceAtMost(document.chapters.lastIndex)
            } }
            Box(Modifier.weight(1f)) { ImportChapterPicker("中文", document.chapters.map { it.title }, chineseIndex, !busy) {
                chineseIndex = it; plans = plans - englishIndex; reviewed = false; exactSource = false
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sentences, enabled = !busy, onClick = { sentences = true }, label = { Text("按句候选") })
            FilterChip(selected = !sentences, enabled = !busy, onClick = { sentences = false }, label = { Text("按段候选") })
            TextButton(enabled = !busy, onClick = { scope.launch {
                busy = true; error = null
                try { change(withContext(Dispatchers.Default) { ChineseImport.propose(book, document, englishIndex, chineseIndex, sentences, clauses) }) }
                catch (e: Exception) { error = e.message } finally { busy = false }
            } }) { Text("生成本章") }
        }
        if (sentences) Row {
            Checkbox(clauses, enabled = !busy, onCheckedChange = { clauses = it })
            Text("中文长句按逗号、分号细分（重新生成生效）", Modifier.padding(top = 12.dp), fontSize = 11.sp)
        }
        if (plans.isEmpty() && book.chapters.size == document.chapters.size && book.chapters.size > 1) {
            TextButton(enabled = !busy, onClick = { scope.launch {
                busy = true; error = null
                try {
                    plans = withContext(Dispatchers.Default) { book.chapters.indices.associateWith { ChineseImport.propose(book, document, it, it, sentences, clauses) } }
                    reviewed = false; exactSource = false
                } catch (e: Exception) { error = e.message } finally { busy = false }
            } }) { Text("按章节顺序生成全书候选（仍需核对）", fontSize = 12.sp) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (current == null) Box(Modifier.weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("先选择对应章节，再生成候选。章节数不同也可逐章选择，不要求两个文件目录完全一致。", fontSize = 13.sp)
                Text("英文开头：\n${book.chapters[englishIndex].paragraphs.firstOrNull { !it.heading }?.text.orEmpty()}")
                Text("中文开头：\n${document.chapters[chineseIndex].paragraphs.firstOrNull().orEmpty()}", color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Text("本章 ${current.english.size} 个英文单元 / ${current.chinese.size} 个中文单元 · ${if (current.paragraphMode) "按段" else if (current.chineseClauses) "中文子句" else "按句"}候选", fontSize = 11.sp)
            Row {
                TextButton(enabled = !busy, onClick = { change(current.copy(pairs = current.pairs.map { it.copy(selected = it.enStart < it.enEnd && it.zhStart < it.zhEnd) })) }) { Text("全选本章", fontSize = 12.sp) }
                TextButton(enabled = !busy, onClick = { change(current.copy(pairs = current.pairs.map { it.copy(selected = false) })) }) { Text("全不选", fontSize = 12.sp) }
                TextButton(enabled = !busy, onClick = { adding = true }) { Text("添加配对", fontSize = 12.sp) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(current.pairs) { index, pair ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Checkbox(pair.selected, enabled = !busy && pair.enStart < pair.enEnd && pair.zhStart < pair.zhEnd,
                                    onCheckedChange = { value -> change(current.copy(pairs = current.pairs.toMutableList().also { it[index] = pair.copy(selected = value) })) })
                                Text("${index + 1} · 英 ${pair.enStart + 1}–${pair.enEnd} / 中 ${pair.zhStart + 1}–${pair.zhEnd}", Modifier.padding(top = 12.dp), fontSize = 12.sp)
                            }
                            if (pair.enStart == pair.enEnd || pair.zhStart == pair.zhEnd) Text("未找到对应，尚未选中；可修改范围。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            Text(current.english.subList(pair.enStart, pair.enEnd).joinToString(" ") { it.text }, fontSize = 14.sp)
                            Text(current.chinese.subList(pair.zhStart, pair.zhEnd).joinToString("") { it.text }, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            Row {
                                TextButton(enabled = !busy, onClick = { editing = index }) { Text("修改范围", fontSize = 12.sp) }
                                if (index < current.pairs.lastIndex) TextButton(enabled = !busy, onClick = {
                                    val next = current.pairs[index + 1]
                                    if (pair.enEnd == next.enStart && pair.zhEnd == next.zhStart) {
                                        val merged = PairDraft(pair.enStart, next.enEnd, pair.zhStart, next.zhEnd, true)
                                        change(current.copy(pairs = current.pairs.toMutableList().also { it[index] = merged; it.removeAt(index + 1) }))
                                    } else error = "两个范围不连续，请使用修改范围。"
                                }) { Text("合并下一组", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
        Row { Checkbox(replace, enabled = !busy, onCheckedChange = { replace = it; reviewed = false }); Text("替换选中范围内的已有译文（默认保留）", Modifier.padding(top = 12.dp), fontSize = 12.sp) }
        Row { Checkbox(reviewed, enabled = !busy && selectedCount > 0, onCheckedChange = { reviewed = it }); Text("已核对选中的 $selectedCount 组配对（${plans.size} 章）", Modifier.padding(top = 12.dp), fontSize = 12.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 3) }
        Button(enabled = !busy && reviewed && selectedCount > 0, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch {
            busy = true; error = null
            try { pending = withContext(Dispatchers.Default) { ChineseImport.prepare(book, plans.values.filter { p -> p.pairs.any { it.selected } }, replace) } }
            catch (e: Exception) { error = e.message } finally { busy = false }
        } }) { Text("预览保存结果") }
    }
    if (current != null && (editing != null || adding)) {
        val index = editing
        val pair = index?.let { current.pairs[it] } ?: PairDraft(0, 1, 0, 1)
        PairRangeEditor(current, pair, onDismiss = { editing = null; adding = false }, onApply = { changed ->
            change(current.copy(pairs = current.pairs.toMutableList().also { if (index == null) it.add(changed) else it[index] = changed }))
            editing = null; adding = false
        })
    }
    pending?.let { result -> AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("保存中文配对") }, text = {
        Text("将保存 ${result.count} 组中文，替换 ${result.conflicts} 组已有译文。\n${result.chapterTitles.joinToString("、")}\n${selectedCount - result.count} 组选中候选因已有译文而保留原状。\n\n英文正文、其他章节和阅读进度保留。可撤销最近一次修改。")
    }, confirmButton = { Button(enabled = !busy, onClick = { scope.launch {
        busy = true
        try { onSave(result.book); pending = null } catch (e: Exception) { pending = null; error = e.message } finally { busy = false }
    } }) { Text(if (busy) "保存中…" else "保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("返回检查") } }) }
}

@Composable
private fun PairRangeEditor(plan: ChapterPairing, pair: PairDraft, onDismiss: () -> Unit, onApply: (PairDraft) -> Unit) {
    var es by remember { mutableStateOf((pair.enStart + 1).toString()) }
    var ee by remember { mutableStateOf(pair.enEnd.coerceAtLeast(pair.enStart + 1).toString()) }
    var zs by remember { mutableStateOf((pair.zhStart + 1).toString()) }
    var ze by remember { mutableStateOf(pair.zhEnd.coerceAtLeast(pair.zhStart + 1).toString()) }
    val startEn = (es.toIntOrNull() ?: 0) - 1; val endEn = ee.toIntOrNull() ?: 0
    val startZh = (zs.toIntOrNull() ?: 0) - 1; val endZh = ze.toIntOrNull() ?: 0
    val valid = startEn >= 0 && endEn > startEn && endEn <= plan.english.size && startZh >= 0 && endZh > startZh && endZh <= plan.chinese.size
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择对应单元范围") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("填写预览中的单元编号。拆分时先缩小本组范围，再用「添加配对」补上其余部分。保存会检查重复范围。", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeNumber(es, { es = it }, "英文起", Modifier.weight(1f)); RangeNumber(ee, { ee = it }, "英文止", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeNumber(zs, { zs = it }, "中文起", Modifier.weight(1f)); RangeNumber(ze, { ze = it }, "中文止", Modifier.weight(1f))
            }
            if (valid) {
                Text(plan.english.subList(startEn, endEn).joinToString(" ") { it.text })
                Text(plan.chinese.subList(startZh, endZh).joinToString("") { it.text }, color = MaterialTheme.colorScheme.primary)
            } else Text("范围应在英文 1–${plan.english.size}、中文 1–${plan.chinese.size} 内", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onApply(PairDraft(startEn, endEn, startZh, endZh)) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RangeNumber(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value, { if (it.length <= 6 && it.all(Char::isDigit)) onChange(it) }, label = { Text(label) }, modifier = modifier,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun ImportChapterPicker(label: String, titles: List<String>, index: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
            Text("$label ${index + 1} · ${titles[index]}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            titles.forEachIndexed { i, title -> DropdownMenuItem(text = { Text("${i + 1}. $title", maxLines = 2) }, onClick = { onChange(i); open = false }) }
        }
    }
}

@Composable
private fun ImportDialog(title: String, busy: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(title, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                }
                content()
            }
        }
    }
}

@Composable
private fun ImportMessage(message: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, text = { Text(message) }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

