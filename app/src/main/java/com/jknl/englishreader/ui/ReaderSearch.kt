@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.jknl.englishreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import com.jknl.englishreader.data.Book
import com.jknl.englishreader.data.PassageRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun ReaderSearch(book: Book, rows: List<ReadingRow>, onDismiss: () -> Unit, onPick: (PassageRange) -> Unit, initialQuery: String = "") {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var hits by remember { mutableStateOf(emptyList<PassageRange>()) }
    var searching by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    LaunchedEffect(query, rows) {
        val term = query.trim()
        hits = emptyList(); truncated = false; searching = term.isNotEmpty()
        if (term.isEmpty()) return@LaunchedEffect
        delay(250)
        val found = withContext(Dispatchers.Default) {
            val results = mutableListOf<PassageRange>()
            for (row in rows) {
                ensureActive()
                var offset = 0
                while (offset < row.paragraph.text.length) {
                    ensureActive()
                    val start = row.paragraph.text.indexOf(term, offset, ignoreCase = true)
                    if (start < 0) break
                    results += PassageRange(row.chapter, row.paragraphIndex, start, start + term.length)
                    if (results.size > 300) return@withContext results
                    offset = start + term.length
                }
            }
            results
        }
        truncated = found.size > 300; hits = found.take(300); searching = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (book.isArticle) "搜索结果" else "搜索本书", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索正文（中文或英文）") },
                modifier = Modifier.fillMaxWidth().testTag("reader-search-input"),
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") } })
            Text(when {
                query.isBlank() -> "输入关键词，点结果跳转到原文"
                searching -> "正在搜索…"
                truncated -> "显示前 300 处结果，请增加关键词缩小范围"
                hits.isEmpty() -> "没有找到匹配的正文"
                else -> "找到 ${hits.size} 处"
            }, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).testTag("reader-search-results"), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(hits, key = { "${it.chapter}:${it.paragraph}:${it.start}" }) { hit ->
                    val text = book.chapters[hit.chapter].paragraphs[hit.paragraph].text
                    var from = (hit.start - 35).coerceAtLeast(0)
                    var to = (hit.end + 65).coerceAtMost(text.length)
                    if (from > 0 && text[from].isLowSurrogate()) from--
                    if (to < text.length && text[to - 1].isHighSurrogate()) to++
                    Column(Modifier.fillMaxWidth().clickable { onPick(hit) }.padding(vertical = 12.dp)) {
                        Text(book.chapters[hit.chapter].title, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(buildAnnotatedString {
                            if (from > 0) append("…")
                            append(text.substring(from, hit.start).replace('\n', ' '))
                            withStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer)) { append(text.substring(hit.start, hit.end)) }
                            append(text.substring(hit.end, to).replace('\n', ' '))
                            if (to < text.length) append("…")
                        })
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** All matches of [term] in reading order, capped so a very common word stays responsive. */
internal fun findInRows(rows: List<ReadingRow>, term: String, limit: Int = 500): List<PassageRange> {
    val t = term.trim()
    if (t.isEmpty()) return emptyList()
    val results = mutableListOf<PassageRange>()
    for (row in rows) {
        var offset = 0
        val text = row.paragraph.text
        while (offset < text.length) {
            val start = text.indexOf(t, offset, ignoreCase = true)
            if (start < 0) break
            results += PassageRange(row.chapter, row.paragraphIndex, start, start + t.length)
            if (results.size >= limit) return results
            offset = start + t.length
        }
    }
    return results
}

/** Find-in-page bar: every match is highlighted in the text; arrows step through them. */
@Composable
internal fun FindBar(query: String, onQuery: (String) -> Unit, index: Int, count: Int, searching: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onList: () -> Unit, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 60.dp).padding(horizontal = 6.dp).testTag("find-bar"),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GlyphButton("close", "关闭搜索", onClose)
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                    androidx.compose.foundation.text.BasicTextField(query, onQuery, singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onNext() }),
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(focus).testTag("reader-search-input"),
                        decorationBox = { inner -> if (query.isEmpty()) Text("在本文中查找", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() })
                    Text(when { query.isBlank() -> ""; searching -> "…"; count == 0 -> "无结果"; else -> "${index + 1}/$count" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onPrevious, enabled = count > 0, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↑", fontSize = 18.sp) }
            TextButton(onClick = onNext, enabled = count > 0, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↓", fontSize = 18.sp) }
            TextButton(onClick = onList, enabled = count > 0, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("列表", fontSize = 13.sp) }
        }
    }
}
