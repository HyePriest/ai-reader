package com.jknl.englishreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The bundled guide is generated from docs/ai-book-preparation.md at build time. */
@Composable
internal fun ImportGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val guide = remember { context.assets.open("ai-book-preparation.md").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    var chinese by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    val prompt = remember(guide, chinese) {
        if (chinese) guide.substringAfter("## 纯中文书").trim()
        else guide.substringAfter("## 可复制的提示词：英文原文 + 已有中文译本").substringBefore("## 纯中文书").trim()
    }
    val json = remember(prompt) { prompt.substringAfter("```json").substringBefore("```").trim() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).safeDrawingPadding().padding(horizontal = 12.dp).testTag("import-guide"),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("AI 制书提示词", modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    GlyphButton("close", "关闭提示词", onDismiss)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !chinese, onClick = { chinese = false; copied = null }, label = { Text("双语书") })
                    FilterChip(selected = chinese, onClick = { chinese = true; copied = null }, label = { Text("纯中文书") })
                }
                Text(if (chinese) "复制提示词 → 连同中文书交给 AI → 下载 JSON → 返回导入。中文 EPUB、TXT、HTML 也可直接导入。"
                    else "复制提示词 → 连同英文原书和中文译本交给 AI → 下载 JSON → 返回导入。原始文件可用 EPUB、TXT、Word 等 AI 能读取的格式。",
                    fontSize = 13.sp, lineHeight = 21.sp)
                key(chinese) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(prompt.substringBefore("```json").trim(), fontSize = 15.sp, lineHeight = 25.sp)
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = MaterialTheme.shapes.medium) {
                                Text(json, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
                            }
                            val after = prompt.substringAfterLast("```").trim()
                            if (after.isNotEmpty()) Text(after, fontSize = 13.sp, lineHeight = 21.sp)
                        }
                    }
                }
                copied?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(json)); copied = "已复制 JSON 示例；示例内容需替换成实际书籍。" }, modifier = Modifier.weight(1f)) { Text("复制 JSON 示例", fontSize = 12.sp) }
                    Button(onClick = { clipboard.setText(AnnotatedString(prompt)); copied = "已复制完整提示词，包含 JSON 格式。" }, modifier = Modifier.weight(1f)) { Text("复制完整提示词", fontSize = 12.sp) }
                }
            }
        }
    }
}
