package com.jknl.englishreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TranslationFileActions(book: Book, chapterIndex: Int, onUpdate: suspend (Book) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportChapterIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var preview by remember { mutableStateOf<TranslationImport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val index = exportChapterIndex
        if (uri != null && index != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val data = TranslationFiles.exportChapter(book, index)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "不能写入文件" }.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                }
                message = "已导出本章待译文件。让翻译工具只填写 zh，保留其他字段；完成后用「导入补充中文」导回这本书。"
            } catch (e: Exception) { message = "导出失败：${e.message}" } finally { busy = false; exportChapterIndex = null }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                preview = withContext(Dispatchers.IO) {
                    val raw = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readLimited(16 * 1024 * 1024).toString(Charsets.UTF_8) }
                    TranslationFiles.prepare(book, raw)
                }
                replace = false
            } catch (e: Exception) { message = "导入失败：${e.message}" } finally { busy = false }
        }
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("当前：${book.chapters[chapterIndex].title}", fontSize = 12.sp)
        DirectChineseImportActions(book, chapterIndex, onUpdate)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起旧版补译工具" else "旧版补译 JSON 工具", fontSize = 12.sp) }
        if (advanced) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { TranslationFiles.exportChapter(book, chapterIndex) }
                        exportChapterIndex = chapterIndex
                        export.launch("chapter-${chapterIndex + 1}-translation.json")
                    } catch (e: Exception) { message = e.message } finally { busy = false }
                }
            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("导出本章待译", fontSize = 12.sp) }
            OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("导入补充中文", fontSize = 12.sp) }
        }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (preview != null) AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("确认补充译文") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val p = preview!!
            Text("${p.count} 个片段 · ${p.chapterTitles.size} 章\n${p.chapterTitles.joinToString("、")}")
            Text("只更新文件中填好的中文，未翻译部分与阅读进度保留。")
            if (p.conflicts > 0) {
                Text("涉及 ${p.conflicts} 组已有译文，替换会移除这些组的完整关联。", color = MaterialTheme.colorScheme.error)
                Row { Checkbox(checked = replace, onCheckedChange = { replace = it }); Text("确认替换已有对应组", modifier = Modifier.padding(top = 12.dp)) }
            }
        }
    }, confirmButton = { Button(enabled = !busy && (preview!!.conflicts == 0 || replace), onClick = {
        scope.launch {
            busy = true
            try { onUpdate(preview!!.book); preview = null; message = "中文已保存，下次点句子即可查看。" }
            catch (e: Exception) { message = "保存失败：${e.message}" } finally { busy = false }
        }
    }) { Text(if (busy) "保存中…" else "保存中文") } }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") } })
    if (message != null) AlertDialog(onDismissRequest = { message = null }, text = { Text(message!!) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } })
}
