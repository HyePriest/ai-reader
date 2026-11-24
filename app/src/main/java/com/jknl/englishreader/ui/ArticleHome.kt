@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jknl.englishreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home for short reading: paste, share or import, then pick an article from a card list. */
@Composable
internal fun ArticleHome(articles: List<ShelfBook>, store: LibraryStore, onRead: (ShelfBook) -> Unit, onOpenText: (String) -> Unit,
    onImport: () -> Unit, onSettings: () -> Unit, onNotebook: () -> Unit, onDelete: (List<ShelfBook>) -> Unit,
    titleStatus: String? = null, onRetryTitles: () -> Unit = {}) {
    var openIssue by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(articles) { articles.filter { it.collection.isNotBlank() }.groupBy { it.collection }.toList().sortedByDescending { it.first } }
    val exams = groups.filter { (_, list) -> list.first().sourceFormat == ExamPaper.FORMAT }
    val issues = groups.filter { (_, list) -> list.first().sourceFormat != ExamPaper.FORMAT }
    val singles = remember(articles) { articles.filter { it.collection.isBlank() }.sortedByDescending { it.importedAt } }
    val issue = openIssue?.let { name -> groups.firstOrNull { it.first == name } }
    if (issue != null) {
        IssueScreen(issue.first, issue.second, store, onBack = { openIssue = null }, onRead = onRead,
            onDelete = { onDelete(it); if (it.size == issue.second.size) openIssue = null })
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
                    Text("读一篇", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif)
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
                        placeholder = { Text("粘贴英文文章、网页链接，或整份真题…") }, minLines = 3, maxLines = 6, shape = RoundedCornerShape(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) onOpenText(clip)
                        }, modifier = Modifier.weight(1f).testTag("read-clipboard")) { Text("读剪贴板", maxLines = 1) }
                        Button(enabled = text.isNotBlank(), onClick = { onOpenText(text); text = "" },
                            modifier = Modifier.weight(1f).testTag("start-reading")) { Text("开始阅读", maxLines = 1) }
                    }
                    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth().testTag("import-article-file")) {
                        Text("导入文件 · 经济学人 EPUB、真题 md、TXT、Word、PDF", fontSize = 13.sp)
                    }
                }
            }
        }
        if (titleStatus != null && articles.any { it.titleZh.isBlank() && it.sourceFormat != ExamPaper.FORMAT }) item(key = "title-status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titleStatus, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (!titleStatus.startsWith("正在")) TextButton(onClick = if (titleStatus.contains("API Key")) onSettings else onRetryTitles) {
                    Text(if (titleStatus.contains("API Key")) "去设置" else "重试")
                }
            }
        }
        if (exams.isNotEmpty()) item(key = "exams-title") { SectionLabel("考研真题") }
        items(exams, key = { "exam-${it.first}" }) { (name, list) ->
            CollectionCard(name, list, store, exam = true, onClick = { openIssue = name }, onLongClick = { confirmDelete = name to list })
        }
        if (issues.isNotEmpty()) item(key = "issues-title") { SectionLabel("经济学人") }
        items(issues, key = { "issue-${it.first}" }) { (name, list) ->
            CollectionCard(name, list, store, exam = false, onClick = { openIssue = name }, onLongClick = { confirmDelete = name to list })
        }
        if (singles.isNotEmpty()) item(key = "singles-title") { SectionLabel("最近的文章") }
        items(singles, key = { it.id }) { article ->
            ArticleCard(article, store, onClick = { onRead(article) }, onLongClick = { confirmDelete = article.title to listOf(article) })
        }
        if (articles.isEmpty()) item(key = "empty") {
            Text("还没有文章。在浏览器、公众号或文件管理器里选「分享 → ai reader」，也能直接打开。\n\n选中一段英文后，在弹出菜单里选「用 ai reader 阅读」同样可以。",
                fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
    confirmDelete?.let { (name, list) -> DeleteDialog(name, list.size, onDismiss = { confirmDelete = null }) { onDelete(list); confirmDelete = null } }
}

@Composable
private fun CollectionCard(name: String, list: List<ShelfBook>, store: LibraryStore, exam: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val answers = remember { ExamAnswers(context) }
    val ordered = remember(list) { list.sortedBy { it.importedAt } }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("collection-$name"), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif)
                val summary = if (exam) {
                    val total = ordered.sumOf { it.questionCount }
                    val done = remember(list) { ordered.sumOf { answers.chosen(it.id).size } }
                    "${ordered.size} 部分 · $total 道选择题 · 已答 $done"
                } else {
                    val read = ordered.count { it.progress(store.position(it)) > .9f }
                    "${ordered.size} 篇 · 已读 $read · 考研常见题材 ${ordered.count { Articles.examTopic(it.section) && Articles.examLength(it.wordCount) }}"
                }
                Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!exam) Text(ordered.take(3).joinToString(" · ") { it.titleZh.ifBlank { it.title } }, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!exam) Thumbnail(ordered.map { it.id }, 84.dp, 84.dp, Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
internal fun IssueScreen(name: String, articles: List<ShelfBook>, store: LibraryStore, onBack: () -> Unit, onRead: (ShelfBook) -> Unit,
    onDelete: (List<ShelfBook>) -> Unit) {
    BackHandler(onBack = onBack)
    val exam = articles.firstOrNull()?.sourceFormat == ExamPaper.FORMAT
    var examOnly by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Pair<String, List<ShelfBook>>?>(null) }
    var keyOpen by remember { mutableStateOf(false) }
    // Import order follows the issue's or paper's own order.
    val ordered = remember(articles) { articles.sortedBy { it.importedAt } }
    val visible = if (examOnly) ordered.filter { Articles.examTopic(it.section) && Articles.examLength(it.wordCount) } else ordered
    val sections = remember(visible) { visible.groupBy { it.section.ifBlank { "其他" } }.toList() }
    val featured = if (!exam && !examOnly) visible.firstOrNull { it.section.equals("Leaders", true) } else null
    LazyColumn(Modifier.fillMaxSize().testTag("issue-screen"), contentPadding = PaddingValues(
        top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 4.dp, bottom = 24.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton("back", "返回", onBack)
                    Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (exam) TextButton(onClick = { keyOpen = true }, modifier = Modifier.testTag("answer-key")) { Text("答案") }
                    TextButton(onClick = { confirmDelete = name to articles }) { Text(if (exam) "删除" else "删除本期") }
                }
                if (!exam) Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !examOnly, onClick = { examOnly = false }, label = { Text("全部 ${articles.size}") })
                    FilterChip(selected = examOnly, onClick = { examOnly = true }, label = { Text("考研常见题材 · 400–1200 词") }, modifier = Modifier.testTag("exam-filter"))
                }
                if (examOnly) Text("按栏目和篇幅粗略筛选：科技、商业、经济、文化、社论等考研阅读常改编的题材。只是题材参考，不代表会考。",
                    fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                if (exam) Text("点一部分进入阅读，右上角「做题」作答，交卷后可以让 AI 逐题讲解。没有填写答案时，由 AI 判断答案，可能有误。",
                    fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        featured?.let { f -> item(key = "featured") { FeaturedCard(f, store, onClick = { onRead(f) }, onLongClick = { confirmDelete = f.title to listOf(f) }) } }
        sections.forEach { (section, list) ->
            val rest = list.filter { it.id != featured?.id }
            if (rest.isEmpty()) return@forEach
            item(key = "section-$section") { SectionLabel(section, Modifier.padding(horizontal = 4.dp)) }
            items(rest, key = { it.id }) { article ->
                if (exam) ExamPartRow(article, onClick = { onRead(article) })
                else ArticleCard(article, store, onClick = { onRead(article) }, onLongClick = { confirmDelete = article.title to listOf(article) })
            }
        }
    }
    confirmDelete?.let { (title, list) -> DeleteDialog(title, list.size, onDismiss = { confirmDelete = null }) { onDelete(list); confirmDelete = null } }
    if (keyOpen) AnswerKeyDialog(name, onDismiss = { keyOpen = false })
}

@Composable
private fun ArticleCard(article: ShelfBook, store: LibraryStore, onClick: () -> Unit, onLongClick: () -> Unit) {
    val progress = remember(article) { article.progress(store.position(article)) }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("article-${article.id}"), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ArticleTitles(article)
                if (article.subtitle.isNotBlank()) Text(article.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleTags(article, progress)
            }
            Thumbnail(listOf(article.id), 96.dp, 72.dp, Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun FeaturedCard(article: ShelfBook, store: LibraryStore, onClick: () -> Unit, onLongClick: () -> Unit) {
    val progress = remember(article) { article.progress(store.position(article)) }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("featured-${article.id}"), shape = RoundedCornerShape(16.dp)) {
        Column {
            Thumbnail(listOf(article.id), Dp.Unspecified, 180.dp, Modifier.fillMaxWidth(), corner = 0.dp)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(article.section, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                ArticleTitles(article, large = true)
                if (article.subtitle.isNotBlank()) Text(article.subtitle, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleTags(article, progress)
            }
        }
    }
}

@Composable
private fun ArticleTitles(article: ShelfBook, large: Boolean = false) {
    Text(article.title, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif, fontWeight = FontWeight.SemiBold,
        fontSize = if (large) 20.sp else 16.sp, lineHeight = if (large) 26.sp else 21.sp)
    if (article.titleZh.isNotBlank()) Text(article.titleZh, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = if (large) 15.sp else 13.sp,
        lineHeight = if (large) 21.sp else 18.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ArticleTags(article: ShelfBook, progress: Float) {
    val tags = listOfNotNull(article.countLabel,
        when { progress > .9f -> "已读完"; progress > .02f -> "读到 ${(progress * 100).toInt()}%"; else -> null },
        if (Articles.examTopic(article.section) && Articles.examLength(article.wordCount)) "考研常见题材" else null,
        if (article.collection.isBlank()) article.author else null)
    Text(tags.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ExamPartRow(part: ShelfBook, onClick: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val answers = remember { ExamAnswers(context) }
    val done = remember(part) { answers.chosen(part.id).size }
    val key = remember(part) { answers.key(part.collection) }
    val right = remember(part) { if (answers.submitted(part.id)) answers.chosen(part.id).count { (n, c) -> key[n] == c } else -1 }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick).testTag("exam-part-${part.id}"), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(part.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(part.subtitle.takeIf { it.isNotBlank() }, part.countLabel).joinToString(" · "), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (part.questionCount > 0) Text(when {
                right >= 0 && key.isNotEmpty() -> "对 $right / ${part.questionCount}"
                done > 0 -> "已答 $done / ${part.questionCount}"
                else -> "${part.questionCount} 题"
            }, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AnswerKeyDialog(collection: String, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val answers = remember { ExamAnswers(context) }
    var text by remember { mutableStateOf(AnswerKey.format(answers.key(collection))) }
    val parsed = AnswerKey.parse(text)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("参考答案") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("粘贴这份真题的答案，交卷后会直接判对错。支持「21-25 DDABA」「21.D 22.B」等写法，只填一部分也可以。", fontSize = 13.sp, lineHeight = 20.sp)
            OutlinedTextField(text, { if (it.length <= 4000) text = it }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("answer-key-input"),
                placeholder = { Text("1-5 BCABD\n21-25 DDABA") })
            Text(if (parsed.isEmpty()) "还没有识别出答案" else "识别到 ${parsed.size} 题：${AnswerKey.format(parsed)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { answers.setKey(collection, parsed); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** The first stored image among [ids]; takes no space when none exists. */
@Composable
private fun Thumbnail(ids: List<String>, width: Dp, height: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val context = LocalContext.current.applicationContext
    val covers = remember { BookCovers(context) }
    val image by produceState<ImageBitmap?>(null, ids) {
        value = withContext(Dispatchers.IO) { ids.firstNotNullOfOrNull { id -> if (covers.file(id).exists()) covers.load(id)?.asImageBitmap() else null } }
    }
    image?.let {
        val size = if (width == Dp.Unspecified) Modifier.height(height) else Modifier.size(width, height)
        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.then(size).clip(RoundedCornerShape(corner)))
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun DeleteDialog(title: String, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除${if (count > 1) " $count 篇" else "这篇"}？") },
        text = { Text("「$title」的阅读进度和保存的 AI 对话也会一起删除。生词本里的词不受影响。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
