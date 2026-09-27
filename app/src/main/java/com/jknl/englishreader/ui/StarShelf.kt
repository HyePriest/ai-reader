@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jknl.englishreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jknl.englishreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val Starlight = Color(0xFFF0ECE2)
internal val MutedStar = Color(0xFFA6BECE)
internal val orbitColors = listOf(Color(0xFFE5C99A), Color(0xFFB1DCFA), Color(0xFFA2D3C3), Color(0xFFD2BDE4))

@Composable
internal fun StarShelf(books: List<ShelfBook>, store: LibraryStore, loading: Boolean, notebookOpen: Boolean,
    onRead: (ShelfBook) -> Unit, onImport: () -> Unit, onSettings: () -> Unit, onNotebook: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val covers = remember { BookCovers(context) }
    val time = remember { ReadingTime(context) }
    // Read after the outgoing Reader has disposed and flushed its final interval.
    val durations by produceState<Map<String, Pair<Long, Long>>>(emptyMap(), books) {
        value = withContext(Dispatchers.IO) { books.associate { it.id to (time.total(it.id) to time.today(it.id)) } }
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val motion = rememberStarlight()
    val starTravel = remember { mutableFloatStateOf(0f) }
    val parallax = remember { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            starTravel.floatValue += consumed.y
            return Offset.Zero
        }
    } }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCoverId by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val visible = remember(books, query) { books.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.author.contains(query.trim(), true) } }
    val clusters = remember(visible) { visible.sortedBy { it.importedAt }.chunked(6) }
    val progress = remember(books) { books.associate { it.id to it.progress(store.position(it)) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingCoverId
        pendingCoverId = null
        if (uri != null && id != null) scope.launch {
            saving = true
            try { withContext(Dispatchers.IO) { covers.save(id, uri) }; revision++ }
            catch (e: Exception) { error = "封面未能保存：${e.message}" }
            finally { saving = false }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF050D18))) {
        LivingStarfield(motion, starTravel)
        LazyColumn(Modifier.fillMaxSize().testTag("shelf").nestedScroll(parallax), state = list,
            contentPadding = PaddingValues(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 24.dp)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ai reader · ${books.size} 本书", fontSize = 12.sp, color = MutedStar, modifier = Modifier.weight(1f))
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" },
                            modifier = Modifier.semantics { contentDescription = "搜索书籍" }) { StarIcon("search") }
                        IconButton(onClick = onNotebook, modifier = Modifier.testTag("shelf-vocabulary")
                            .semantics { contentDescription = "生词本" }) { Text("词", fontSize = 17.sp, color = Starlight) }
                        TextButton(onClick = onImport) { Text("导入", fontSize = 13.sp, color = Starlight) }
                        GlyphButton("settings", "阅读设置", onSettings)
                    }
                    if (searching) OutlinedTextField(query, { query = it }, label = { Text("书名或作者") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(16.dp))
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (loading) item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 1.dp)
                    Text("正在打开书架…", color = MutedStar, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                }
            }
            if (!loading && visible.isEmpty()) item {
                Text(if (query.isNotBlank()) "没有找到这本书" else "暂无书籍", color = MutedStar,
                    modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
            }
            items(clusters, key = { "stars-${it.first().id}" }) { cluster ->
                BookConstellation(cluster, progress, motion, detailsId, onSelect = { detailsId = it.id }, onRead = onRead)
            }
        }
    }
    books.firstOrNull { it.id == detailsId }?.let { book ->
        // Keep the detail window stationary; only its content owns vertical scrolling.
        Dialog(onDismissRequest = { if (!saving) detailsId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).safeDrawingPadding().padding(horizontal = 12.dp).testTag("book-details-dialog"),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("书籍详情", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(enabled = !saving, onClick = { detailsId = null }, modifier = Modifier.semantics { contentDescription = "关闭书籍详情" }) { Text("×", fontSize = 27.sp) }
                }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).testTag("book-details-scroll").padding(horizontal = 28.dp).padding(top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OrbitCover(book, covers, revision, orbitColors[0], Modifier.size(126.dp))
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 24.sp, textAlign = TextAlign.Center)
                if (book.author.isNotBlank()) Text(book.author, fontSize = 13.sp, color = MutedStar)
                val position = store.position(book)
                val fraction = book.progress(position)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = orbitColors[book.id.hashCode().ushr(1) % orbitColors.size], drawStopIndicator = {})
                Text("已读 ${(fraction * 100).roundToInt()}% · 第 ${position.chapter + 1} 章", fontSize = 12.sp, color = MutedStar)
                Button(onClick = { detailsId = null; onRead(book) }, modifier = Modifier.fillMaxWidth().testTag("read-selected-book")) {
                    Text(if (fraction > 0f) "继续阅读" else "开始阅读")
                }
                HorizontalDivider(color = Color(0x335F8DAC))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("累计阅读", fontSize = 12.sp, color = MutedStar); Text(readingDuration(durations[book.id]?.first ?: 0), modifier = Modifier.padding(top = 8.dp), fontSize = 19.sp) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("今日阅读", fontSize = 12.sp, color = MutedStar); Text(readingDuration(durations[book.id]?.second ?: 0), modifier = Modifier.padding(top = 8.dp), fontSize = 19.sp) }
                }
                Text("全书 ${book.countLabel} · ${book.chapters.size} 节\n${if (book.isChinese) "中文阅读" else "${book.chapters.count { it.translated }} 节配有中文"}", fontSize = 13.sp, textAlign = TextAlign.Center, color = MutedStar, lineHeight = 23.sp)
                OutlinedButton(onClick = { pendingCoverId = book.id; picker.launch(arrayOf("image/*")) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在保存封面…" else "从相册选择封面") }
                TextButton(onClick = { scope.launch { saving = true; try { withContext(Dispatchers.IO) { covers.remove(book.id) }; revision++ }
                    catch (e: Exception) { error = "封面未能移除：${e.message}" }; saving = false } }, enabled = !saving) { Text("恢复默认封面") }
            }
            }
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("封面") }, text = { Text(error!!) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } })
}

@Composable
private fun OrbitCover(book: ShelfBook, covers: BookCovers, revision: Int, tint: Color, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, book.id, revision) { value = withContext(Dispatchers.IO) { covers.load(book.id)?.asImageBitmap() } }
    Box(modifier.border(1.3.dp, tint.copy(alpha = .8f), CircleShape).padding(4.dp).clip(CircleShape)
        .background(Brush.linearGradient(listOf(tint.copy(alpha = .4f), Color(0xFF0A1728), tint.copy(alpha = .18f)))), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, "${book.title}封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * .65f, size.height * .32f)
                drawCircle(tint.copy(alpha = .18f), size.width * .32f, center, style = Stroke(.6.dp.toPx()))
                drawCircle(tint.copy(alpha = .14f), size.width * .45f, center, style = Stroke(.6.dp.toPx()))
                for (i in 0..10) {
                    val x = ((i * 37 + 17) % 97) / 100f * size.width
                    val y = ((i * 53 + 9) % 97) / 100f * size.height
                    drawCircle(tint.copy(alpha = .35f), if (i % 3 == 0) 1.dp.toPx() else .6.dp.toPx(), Offset(x, y))
                }
            }
            Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val words = book.title.trim().split(Regex("\\s+"))
                val monogram = if (words.size > 1) words.take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase()
                    else book.title.take(2).uppercase()
                Text(monogram, fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 34.sp, maxLines = 1,
                    textAlign = TextAlign.Center, color = Starlight, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun StarIcon(kind: String) {
    Canvas(Modifier.size(21.dp)) {
        if (kind == "search") {
            drawCircle(Starlight, size.width * .32f, Offset(size.width * .4f, size.height * .4f), style = Stroke(1.5.dp.toPx()))
            drawLine(Starlight, Offset(size.width * .65f, size.height * .65f), Offset(size.width * .94f, size.height * .94f), 1.5.dp.toPx())
        }
    }
}


