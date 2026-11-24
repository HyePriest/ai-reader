@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jknl.englishreader.ui

import android.content.Context
import android.app.Activity
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment as UiAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.theme.EnglishReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun ReaderApp(incoming: android.content.Intent? = null, onIncomingHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { LibraryStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val shelfState = rememberSaveableStateHolder()
    var books by remember { mutableStateOf<List<ShelfBook>>(emptyList()) }
    var activeBook by remember { mutableStateOf<Book?>(null) }
    var settings by remember { mutableStateOf(store.settings()) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }
    var shelfLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var importInfo by remember { mutableStateOf(false) }
    var importGuide by rememberSaveable { mutableStateOf(false) }
    var pendingBook by remember { mutableStateOf<Book?>(null) }
    var annotationUpdate by remember { mutableStateOf<Pair<Book, Book>?>(null) }
    var notebookOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("articles") }
    val lightPalette = settings.palette !in setOf("night", "ink", "black")
    SideEffect {
        val window = (context as? Activity)?.window
        if (window != null) WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = (activeBook != null || tab == "articles") && lightPalette
            isAppearanceLightNavigationBars = (activeBook != null || tab == "articles") && lightPalette
        }
    }
    fun openSaved(book: Book) { activeBook = book; activeId = book.id }
    fun addToList(added: List<Book>) { if (added.isNotEmpty()) books = added.map { ShelfBook.from(it) } + books.filter { b -> added.none { it.id == b.id } } }
    /** Pasted or shared text, or a single link: becomes an article and opens immediately. */
    fun openText(raw: String) {
        scope.launch {
            busy = if (Articles.isLinkOnly(raw)) "正在打开网页并提取正文…" else "正在整理文章…"
            try {
                val book = withContext(Dispatchers.IO) {
                    val url = Articles.firstUrl(raw)
                    val article = if (url != null && Articles.isLinkOnly(raw)) {
                        val page = org.jsoup.Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Mobile")
                            .timeout(20_000).maxBodySize(8 * 1024 * 1024).followRedirects(true).get()
                        Articles.fromHtml(page.outerHtml(), url)
                    } else Articles.fromText(raw)
                    if (store.exists(article.id)) store.open(article.id) else article.also { store.save(it) }
                }
                addToList(listOf(book)); tab = "articles"; openSaved(book)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: java.io.IOException) { error = "网页没有打开：${e.message ?: "网络错误"}。可以在浏览器里复制正文，再粘贴进来。" }
            catch (e: Exception) { error = e.message ?: "这段文字没能打开" }
            finally { busy = "" }
        }
    }
    /** Files: an Economist issue splits into articles; short documents from the article tab open as an article. */
    fun importUri(uri: android.net.Uri, asArticle: Boolean) {
        scope.launch {
            busy = "正在整理…"
            try {
                when (val result = withContext(Dispatchers.IO) { store.importAny(uri) }) {
                    is ImportResult.Issue -> {
                        val added = withContext(Dispatchers.IO) { store.saveNew(result.articles) }
                        addToList(added); tab = "articles"
                        val name = result.articles.first().collection
                        notice = "已导入 $name：共 ${result.articles.size} 篇" + if (added.size < result.articles.size) "（${result.articles.size - added.size} 篇已在，进度保留）" else ""
                    }
                    is ImportResult.Single -> {
                        val book = result.book
                        if (asArticle && book.wordCount in 1..15_000) {
                            val article = Articles.fromBook(book)
                            val opened = withContext(Dispatchers.IO) {
                                if (book.sourceFormat == "pdf" && books.none { it.id == book.id }) runCatching { store.delete(book.id) }
                                if (store.exists(article.id)) store.open(article.id) else article.also { store.save(it) }
                            }
                            addToList(listOf(opened)); tab = "articles"; openSaved(opened)
                        } else if (books.any { it.id == book.id }) {
                            val before = withContext(Dispatchers.IO) { store.open(book.id) }
                            val after = BookAnnotations.supplement(before, book)
                            if (before == after) notice = "这本书已经在书架上，已有译文、注释和进度已保留。"
                            else annotationUpdate = before to after
                        } else {
                            if (asArticle) notice = "这个文件超过 1.5 万词，已按书籍导入。"
                            tab = "shelf"; pendingBook = book
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = "导入失败：${e.message ?: "无法读取文件"}" }
            finally { busy = "" }
        }
    }
    fun delete(list: List<ShelfBook>) {
        scope.launch {
            try { withContext(Dispatchers.IO) { list.forEach { store.delete(it.id) } } }
            catch (e: Exception) { error = "删除没有完成：${e.message}" }
            val ids = list.map { it.id }.toSet()
            books = books.filterNot { it.id in ids }
        }
    }
    LaunchedEffect(incoming) {
        val intent = incoming ?: return@LaunchedEffect
        onIncomingHandled()
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        val text = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
        activeId = null; activeBook = null
        when {
            stream != null -> importUri(stream, asArticle = true)
            !text.isNullOrBlank() -> openText(text)
            intent.action == android.content.Intent.ACTION_VIEW && intent.data != null -> importUri(intent.data!!, asArticle = true)
        }
    }

    LaunchedEffect(Unit) {
        val started = SystemClock.elapsedRealtime()
        try {
            val result = withContext(Dispatchers.IO) {
                store.shelf().let { loaded ->
                    if (loaded.books.isEmpty() && loaded.unreadable.isEmpty()) {
                        store.save(store.sample()); store.shelf()
                    } else loaded
                }
            }
            books = result.books
            if (result.unreadable.isNotEmpty()) error = "${result.unreadable.size} 本书暂时无法读取，其余书籍可以继续打开。原文件已保留。"
            android.util.Log.i("ReaderPerf", "shelf-ready=${SystemClock.elapsedRealtime() - started}ms books=${books.size}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "书架未能打开：${e.message}。原文件已保留。" }
        finally { shelfLoading = false }
    }
    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        if (activeBook?.id == id) return@LaunchedEffect
        store.cached(id)?.let { activeBook = it; return@LaunchedEffect }
        busy = "正在打开这本书…"
        try { activeBook = withContext(Dispatchers.IO) { store.open(id) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "书籍未能打开：${e.message}"; activeId = null }
        finally { busy = "" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri, asArticle = false)
    }
    val articleImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri, asArticle = true)
    }
    EnglishReaderTheme(if (activeBook == null && tab == "shelf") "stars" else settings.palette) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val active = activeBook
            Box(Modifier.fillMaxSize()) {
                if (active == null) Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (tab == "articles") shelfState.SaveableStateProvider("articles") {
                            ArticleHome(books.filter { it.isArticle }, store, onRead = { activeId = it.id; activeBook = store.cached(it.id) },
                                onOpenText = ::openText, onImport = { articleImporter.launch(arrayOf("*/*")) }, onSettings = { settingsOpen = true },
                                onNotebook = { notebookOpen = true }, onDelete = ::delete)
                        } else shelfState.SaveableStateProvider("shelf") {
                            StarShelf(books.filterNot { it.isArticle }, store, shelfLoading, notebookOpen,
                                { activeId = it.id; activeBook = store.cached(it.id) }, { if (!shelfLoading) importInfo = true }, { settingsOpen = true }, { notebookOpen = true })
                        }
                    }
                    NavigationBar(Modifier.testTag("home-tabs")) {
                        NavigationBarItem(selected = tab == "articles", onClick = { tab = "articles" }, icon = { Text("文", fontSize = 18.sp) }, label = { Text("文章") },
                            modifier = Modifier.testTag("tab-articles"))
                        NavigationBarItem(selected = tab == "shelf", onClick = { tab = "shelf" }, icon = { Text("书", fontSize = 18.sp) }, label = { Text("书架") },
                            modifier = Modifier.testTag("tab-shelf"))
                    }
                }
                else key(active.id) {
                    Reader(active, store, settings, onBack = { activeId = null; activeBook = null }, onSettings = { settingsOpen = true }, trackingEnabled = !settingsOpen,
                        onUpdate = { updated ->
                            withContext(Dispatchers.IO) { store.saveWithUndo(active, updated) }
                            activeBook = updated
                            books = books.map { if (it.id == updated.id) ShelfBook.from(updated) else it }
                        })
                }
                if (busy.isNotEmpty()) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .92f)).clickable(enabled = true) {}, contentAlignment = UiAlignment.Center) {
                    Column(horizontalAlignment = UiAlignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text(busy, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (settingsOpen) SettingsSheet(settings, { settings = it; store.saveSettings(it) }, { settingsOpen = false })
        if (notebookOpen) WordNotebookDialog { notebookOpen = false }
        if (importGuide) ImportGuideDialog { importGuide = false; importInfo = true }
        if (importInfo) AlertDialog(onDismissRequest = { importInfo = false }, title = { Text("把一本书带进来") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("直接导入电子书\n支持 EPUB、PDF、MOBI / AZW / AZW3、TXT、FB2、DOCX 和 RTF。中文和英文都能独立阅读，无须先用 AI 整理。")
                Text("PDF 可查看原页。有文字的 PDF 也能提取正文；扫描页可放大阅读，暂不支持查词和选句。", style = MaterialTheme.typography.bodySmall)
                Text("需要双语时，可让 AI 匹配原文与译本，并提取材料中已有的注释；也可为英文原书补译。")
                OutlinedButton(onClick = { importInfo = false; importGuide = true }, modifier = Modifier.fillMaxWidth().testTag("view-import-guide")) {
                    Text("查看并复制提示词 · JSON 示例")
                }
                Text("已有的书籍 JSON 和 HTML 仍可导入。文字正文中按住音量减约 0.3 秒，可框选原文后直接提问。")
                Text("长书需要分次补充时，仍可使用书内的「章节与中文」。中英交替且无明确标记的文件会显示配对预览。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(onClick = { importInfo = false; importer.launch(arrayOf("*/*")) }) { Text("选择书籍文件") } }, dismissButton = { TextButton(onClick = { importInfo = false }) { Text("取消") } })
        pendingBook?.let { candidate -> BookImportPreview(candidate, onDismiss = { pendingBook = null }, onSave = { confirmed ->
            withContext(Dispatchers.IO) { store.save(confirmed) }
            books = listOf(ShelfBook.from(confirmed)) + books
            pendingBook = null
            notice = "已导入《${confirmed.title}》 · ${confirmed.chapters.size} 节"
        }) }
        annotationUpdate?.let { (before, after) ->
            val changed = before.chapters.zip(after.chapters).flatMap { (a, b) -> a.paragraphs.zip(b.paragraphs) }
                .filter { (a, b) -> a.annotations != b.annotations }
            AlertDialog(onDismissRequest = { annotationUpdate = null }, title = { Text("更新随书注释") }, text = {
                Text("《${before.title}》已在书架上。将更新 ${changed.size} 段的 ${changed.sumOf { it.second.annotations.size }} 条注释。\n\n这些段落的旧注释会被替换；文件中没有提供注释的段落保持原样。正文、已有译文和阅读进度保留。")
            }, confirmButton = { Button(onClick = {
                annotationUpdate = null
                scope.launch {
                    busy = "正在保存注释…"
                    try {
                        withContext(Dispatchers.IO) { store.saveWithUndo(before, after) }
                        if (activeBook?.id == after.id) activeBook = after
                        notice = "随书注释已更新。"
                    } catch (e: Exception) { error = "注释保存失败：${e.message}" }
                    finally { busy = "" }
                }
            }) { Text("更新注释") } }, dismissButton = { TextButton(onClick = { annotationUpdate = null }) { Text("取消") } })
        }
        if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("暂时没有完成") }, text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } })
        if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text("书架") }, text = { Text(notice!!) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("好的") } })
    }
}

@Composable
internal fun TranslationEditor(book: Book, chapterIndex: Int, initial: List<SourceRange>, onDismiss: () -> Unit, onSave: suspend (Book) -> Unit) {
    val chapter = book.chapters[chapterIndex]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val matching = chapter.alignments.firstOrNull { it.ranges == initial }
    var chinese by remember { mutableStateOf(matching?.chinese.orEmpty()) }
    var paragraphMode by remember { mutableStateOf(false) }
    var from by remember { mutableIntStateOf(chapter.paragraphs.indexOfFirst { it.id == initial.first().paragraph }.coerceAtLeast(0)) }
    var to by remember { mutableIntStateOf(chapter.paragraphs.indexOfFirst { it.id == initial.last().paragraph }.coerceAtLeast(from)) }
    var confirmReplace by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ranges = if (paragraphMode) chapter.paragraphs.subList(from, to + 1).map { SourceRange(it.id, 0, it.text.length) } else initial
    val overlap = chapter.alignments.filter { a -> a.ranges.any { r -> ranges.any { Alignments.overlaps(r, it) } } }
    val english = ranges.joinToString("\n\n") { r -> chapter.paragraphs.first { it.id == r.paragraph }.text.substring(r.start, r.end) }
    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            saving = true
            try {
                chinese = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        val bytes = it.readBytesLimited(512 * 1024)
                        val value = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                        require(!value.contains('\uFFFD')) { "请使用 UTF-8 编码的 TXT" }
                        value
                    } ?: error("无法读取中文文件")
                }
            } catch (e: Exception) { error = e.message } finally { saving = false }
        }
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 12.dp).imePadding(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = UiAlignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("为这一段，配上中文", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(chapter.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = !saving, onClick = onDismiss) { Glyph("close", Modifier.size(18.dp)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = UiAlignment.CenterVertically) {
                        Text("英文范围", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        FilterChip(selected = paragraphMode, onClick = { paragraphMode = !paragraphMode; confirmReplace = false }, label = { Text("按段落选择") })
                    }
                    if (paragraphMode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ParagraphPicker("起", from, chapter, Modifier.weight(1f)) { from = it; if (to < from) to = from; confirmReplace = false }
                        ParagraphPicker("止", to, chapter, Modifier.weight(1f)) { to = it; if (from > to) from = to; confirmReplace = false }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = RoundedCornerShape(12.dp)) {
                        Text(english, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 24.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(14.dp))
                    }
                    Row(verticalAlignment = UiAlignment.CenterVertically) {
                        Text("对应中文", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(enabled = !saving, onClick = { txtPicker.launch(arrayOf("text/plain")) }) { Text("从 TXT 读取") }
                    }
                    OutlinedTextField(value = chinese, onValueChange = { if (it.length <= 200_000) chinese = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 9,
                        placeholder = { Text("粘贴这个英文范围对应的中文译文…") }, shape = RoundedCornerShape(14.dp), enabled = !saving)
                    Text("这次保存会建立一个对应组。选中多句时，点击其中任一句都会显示这段中文；不会自动把整章逐句对齐。", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (overlap.isNotEmpty()) {
                        Text("所选范围涉及 ${overlap.size} 组已有译文。替换会移除这些组的完整关联（包括组内其他句子）。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().clickable { confirmReplace = !confirmReplace }, verticalAlignment = UiAlignment.CenterVertically) {
                            Checkbox(checked = confirmReplace, onCheckedChange = { confirmReplace = it })
                            Text("我确认替换这些对应组", fontSize = 13.sp)
                        }
                    }
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Button(enabled = !saving && chinese.isNotBlank() && (overlap.isEmpty() || confirmReplace), onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val alignment = Alignment(UUID.randomUUID().toString(), ranges, chinese.trim(), "手动配对")
                            val updated = book.copy(chapters = book.chapters.mapIndexed { i, c -> if (i == chapterIndex) Alignments.replacing(c, alignment) else c })
                            onSave(updated)
                        } catch (e: Exception) { error = "保存失败：${e.message}" } finally { saving = false }
                    }
                }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text(if (saving) "正在处理…" else "保存对应关系") }
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var n = read(buffer)
    while (n != -1) { require(out.size() + n <= limit) { "单次中文文件请小于 512 KB" }; out.write(buffer, 0, n); n = read(buffer) }
    return out.toByteArray()
}

@Composable
private fun ParagraphPicker(label: String, selected: Int, chapter: Chapter, modifier: Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：第 ${selected + 1} 段") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp).width(290.dp)) {
            chapter.paragraphs.forEachIndexed { i, p -> DropdownMenuItem(text = { Text("${i + 1}. ${p.text}", maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { onSelect(i); open = false }) }
        }
    }
}

@Composable
private fun SettingsSheet(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("让阅读适合你", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("外观与排版", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("paper" to "纸色", "white" to "明亮", "night" to "夜读").forEach { (value, label) ->
                    FilterChip(selected = settings.palette == value, onClick = { onChange(settings.copy(palette = value)) }, label = { Text(label) })
                }
            }
            listOf(listOf("sage" to "鼠尾草", "blue" to "雾蓝", "rose" to "浅玫瑰"), listOf("sand" to "暖沙", "ink" to "墨蓝", "black" to "纯黑")).forEach { palettes ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palettes.forEach { (value, label) ->
                        FilterChip(selected = settings.palette == value, onClick = { onChange(settings.copy(palette = value)) }, label = { Text(label) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = settings.serif, onClick = { onChange(settings.copy(serif = true)) }, label = { Text("Serif 衬线", fontFamily = FontFamily.Serif) })
                FilterChip(selected = !settings.serif, onClick = { onChange(settings.copy(serif = false)) }, label = { Text("Sans 无衬线") })
            }
            Text("字号   ${settings.size.toInt()}", fontSize = 14.sp)
            Slider(value = settings.size, onValueChange = { onChange(settings.copy(size = it)) }, valueRange = 16f..30f, steps = 13)
            Text("行距   ${"%.1f".format(settings.spacing)}", fontSize = 14.sp)
            Slider(value = settings.spacing, onValueChange = { onChange(settings.copy(spacing = it)) }, valueRange = 1.3f..2.2f, steps = 8)
            Text("ai reader · 给阅读留一点思考的空间。", fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif, fontSize = settings.size.sp, lineHeight = (settings.size * settings.spacing).sp)
            Text("设置自动保存，应用于所有书籍。向下读时隐藏标题，回滑或轻点留白恢复。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DeepSeekSettingsCard()
        }
    }
}

@Composable
internal fun GlyphButton(name: String, description: String, action: () -> Unit) {
    IconButton(onClick = action) { IconGlyph(name, description) }
}

@Composable
private fun IconGlyph(name: String, description: String) {
    Box(Modifier.semantics { this.contentDescription = description }) { Glyph(name, Modifier.size(21.dp)) }
}

@Composable
private fun Glyph(name: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val u = size.width / 24
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.65f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (name) {
            "plus" -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "back" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "forward" -> { line(8f, 5f, 15f, 12f); line(15f, 12f, 8f, 19f) }
            "search" -> { drawCircle(color, 6f * u, Offset(10f * u, 10f * u), style = androidx.compose.ui.graphics.drawscope.Stroke(1.65f * u)); line(15f, 15f, 21f, 21f) }
            "list" -> { for (y in listOf(6f, 12f, 18f)) { line(4f, y, 5f, y); line(9f, y, 20f, y) } }
            "type" -> { line(3f, 19f, 9f, 5f); line(9f, 5f, 15f, 19f); line(5f, 14f, 13f, 14f); line(17f, 11f, 23f, 11f); line(20f, 11f, 20f, 19f) }
            "settings" -> { for (y in listOf(6f, 12f, 18f)) line(3f, y, 21f, y); for ((x, y) in listOf(8f to 6f, 16f to 12f, 10f to 18f)) drawCircle(color, 2.3f * u, Offset(x * u, y * u)) }
            else -> { line(4f, 4f, 4f, 20f); line(20f, 4f, 20f, 20f); line(4f, 4f, 12f, 6f); line(12f, 6f, 20f, 4f); line(4f, 20f, 12f, 22f); line(12f, 22f, 20f, 20f); line(12f, 6f, 12f, 22f) }
        }
    }
}


