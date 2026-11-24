@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jknl.englishreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*

/** Home for short reading: paste, share or import, then start reading at once. */
@Composable
internal fun ArticleHome(articles: List<ShelfBook>, store: LibraryStore, onRead: (ShelfBook) -> Unit, onOpenText: (String) -> Unit,
    onImport: () -> Unit, onSettings: () -> Unit, onNotebook: () -> Unit, onDelete: (List<ShelfBook>) -> Unit) {
    var openIssue by rememberSaveable { mutableStateOf<String?>(null) }
    val issues = remember(articles) { articles.filter { it.collection.isNotBlank() }.groupBy { it.collection }.toList().sortedByDescending { it.first } }
    val singles = remember(articles) { articles.filter { it.collection.isBlank() }.sortedByDescending { it.importedAt } }
    val issue = openIssue?.let { name -> issues.firstOrNull { it.first == name } }
    if (issue != null) {
        IssueScreen(issue.first, issue.second, store, onBack = { openIssue = null }, onRead = onRead, onDelete = { onDelete(it); if (it.size == issue.second.size) openIssue = null })
        return
    }
    var text by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Pair<String, List<ShelfBook>>?>(null) }
    val clipboard = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().testTag("article-home"), contentPadding = PaddingValues(
        top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("读一篇", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
                    Text("粘贴、分享或导入，打开就读", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onNotebook, modifier = Modifier.testTag("article-vocabulary")) { Text("生词本") }
                GlyphButton("settings", "阅读设置", onSettings)
            }
        }
        item(key = "paste") {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { if (it.length <= Articles.MAX_TEXT) text = it }, modifier = Modifier.fillMaxWidth().testTag("article-input"),
                        placeholder = { Text("粘贴英文文章，或一个网页链接…") }, minLines = 3, maxLines = 6, shape = RoundedCornerShape(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) onOpenText(clip) else text = ""
                        }, modifier = Modifier.weight(1f).testTag("read-clipboard")) { Text("读剪贴板", maxLines = 1) }
                        Button(enabled = text.isNotBlank(), onClick = { onOpenText(text); text = "" },
                            modifier = Modifier.weight(1f).testTag("start-reading")) { Text("开始阅读", maxLines = 1) }
                    }
                    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth().testTag("import-article-file")) {
                        Text("导入文件 · 经济学人 EPUB、TXT、Word、PDF")
                    }
                }
            }
        }
        if (issues.isNotEmpty()) item(key = "issues-title") { SectionLabel("经济学人") }
        items(issues, key = { "issue-${it.first}" }) { (name, list) ->
            val read = list.count { it.progress(store.position(it)) > .9f }
            ListItem(headlineContent = { Text(name.removePrefix("The Economist · ").let { "The Economist · $it" }, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("${list.size} 篇 · 已读 $read · 考研常见题材 ${list.count { Articles.examTopic(it.section) }}", fontSize = 12.sp) },
                modifier = Modifier.combinedClickable(onClick = { openIssue = name }, onLongClick = { confirmDelete = name to list }).testTag("issue-$name"))
        }
        if (singles.isNotEmpty()) item(key = "singles-title") { SectionLabel("最近的文章") }
        items(singles, key = { it.id }) { article ->
            ArticleRow(article, store, showSection = false, onClick = { onRead(article) }, onLongClick = { confirmDelete = article.title to listOf(article) })
        }
        if (articles.isEmpty()) item(key = "empty") {
            Text("还没有文章。在浏览器、公众号或文件管理器里选「分享 → ai reader」，也能直接打开。\n\n选中一段英文后，在弹出菜单里选「用 ai reader 阅读」同样可以。",
                fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
    confirmDelete?.let { (name, list) -> DeleteDialog(name, list.size, onDismiss = { confirmDelete = null }) { onDelete(list); confirmDelete = null } }
}

@Composable
private fun IssueScreen(name: String, articles: List<ShelfBook>, store: LibraryStore, onBack: () -> Unit, onRead: (ShelfBook) -> Unit,
    onDelete: (List<ShelfBook>) -> Unit) {
    BackHandler(onBack = onBack)
    var examOnly by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Pair<String, List<ShelfBook>>?>(null) }
    // Import order follows the issue's own table of contents.
    val ordered = remember(articles) { articles.sortedBy { it.importedAt } }
    val visible = if (examOnly) ordered.filter { Articles.examTopic(it.section) && Articles.examLength(it.wordCount) } else ordered
    val sections = remember(visible) { visible.groupBy { it.section.ifBlank { "其他" } }.toList() }
    LazyColumn(Modifier.fillMaxSize().testTag("issue-screen"), contentPadding = PaddingValues(
        top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 4.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)) {
        item(key = "header") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton("back", "返回", onBack)
                    Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { confirmDelete = name to articles }) { Text("删除本期") }
                }
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !examOnly, onClick = { examOnly = false }, label = { Text("全部 ${articles.size}") })
                    FilterChip(selected = examOnly, onClick = { examOnly = true }, label = { Text("考研常见题材 · 400–1200 词") }, modifier = Modifier.testTag("exam-filter"))
                }
                if (examOnly) Text("按栏目和篇幅粗略筛选：科技、商业、经济、文化、社论等考研阅读常改编的题材。只是题材参考，不代表会考。",
                    fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        sections.forEach { (section, list) ->
            item(key = "section-$section") { SectionLabel(section, Modifier.padding(horizontal = 8.dp)) }
            items(list, key = { it.id }) { article ->
                ArticleRow(article, store, showSection = false, onClick = { onRead(article) }, onLongClick = { confirmDelete = article.title to listOf(article) })
            }
        }
    }
    confirmDelete?.let { (title, list) -> DeleteDialog(title, list.size, onDismiss = { confirmDelete = null }) { onDelete(list); confirmDelete = null } }
}

@Composable
private fun ArticleRow(article: ShelfBook, store: LibraryStore, showSection: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val progress = remember(article) { article.progress(store.position(article)) }
    ListItem(
        headlineContent = { Text(article.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif, fontSize = 16.sp) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (article.subtitle.isNotBlank()) Text(article.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                val tags = listOfNotNull(if (showSection) article.section.takeIf { it.isNotBlank() } else null, article.countLabel,
                    when { progress > .9f -> "已读完"; progress > .02f -> "读到 ${(progress * 100).toInt()}%"; else -> null },
                    if (Articles.examTopic(article.section) && Articles.examLength(article.wordCount)) "考研常见题材" else null,
                    if (article.collection.isBlank()) article.author else null)
                Text(tags.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("article-${article.id}"))
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun DeleteDialog(title: String, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除${if (count > 1) " $count 篇文章" else "这篇文章"}？") },
        text = { Text("「$title」的阅读进度和保存的 AI 对话也会一起删除。生词本里的词不受影响。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
