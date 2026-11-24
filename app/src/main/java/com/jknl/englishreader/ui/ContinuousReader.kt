@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jknl.englishreader.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.jknl.englishreader.MainActivity
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jknl.englishreader.data.*
import com.jknl.englishreader.data.Paragraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReadingRow(val chapter: Int, val paragraphIndex: Int, val paragraph: Paragraph) {
    val key: String get() = "$chapter:${paragraph.id}"
}

@Composable
internal fun Reader(book: Book, store: LibraryStore, settings: ReaderSettings, onBack: () -> Unit,
    onSettings: () -> Unit, trackingEnabled: Boolean = true, onUpdate: suspend (Book) -> Unit) {
    val context = LocalContext.current
    val repository = remember { WordRepository(context.applicationContext) }
    val notebook = remember { WordNotebook(context.applicationContext) }
    var notebookOpen by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val rows = remember(book.chapters) { book.chapters.flatMapIndexed { ci, c -> c.paragraphs.mapIndexed { pi, p -> ReadingRow(ci, pi, p) } } }
    val initial = remember { store.position(book) }
    val initialIndex = remember { rows.indexOfFirst { it.chapter == initial.chapter && it.paragraphIndex == initial.paragraph }.coerceAtLeast(0) }
    val list = rememberLazyListState(initialIndex)
    var chapterIndex by remember { mutableIntStateOf(initial.chapter) }
    var chapterProgress by remember { mutableFloatStateOf(0f) }
    var contents by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchHit by remember { mutableStateOf<PassageRange?>(null) }
    var searchJump by remember { mutableStateOf<ReadingPosition?>(null) }
    var pdfOriginal by rememberSaveable { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var lastTap by remember { mutableStateOf<Pair<Pair<Int, SourceRange>, Long>?>(null) }
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var wordMode by remember { mutableStateOf(false) }
    var wordResult by remember { mutableStateOf<WordResult?>(null) }
    var wordError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<Int, List<SourceRange>>?>(null) }
    var jump by remember { mutableStateOf<Int?>(null) }
    var selectingAi by remember { mutableStateOf(false) }
    var aiRanges by remember { mutableStateOf(emptyList<PassageRange>()) }
    var aiPreview by remember { mutableStateOf(emptyList<PassageRange>()) }
    var aiQuestion by remember { mutableStateOf<ReadingQuestion?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiInitial by remember { mutableStateOf<String?>(null) }
    val chats = remember { ArticleChats(context.applicationContext) }
    fun askArticle(question: ReadingQuestion, initial: String? = null) { aiInitial = initial; aiRanges = question.passages; aiQuestion = question }
    var annotation by remember { mutableStateOf<Pair<Paragraph, Int>?>(null) }
    ReadingSession(book.id, trackingEnabled && !contents && !searchOpen && !notebookOpen && editing == null && !pdfOriginal)
    if (pdfOriginal) PdfOriginalDialog(book, chapterIndex) { pdfOriginal = false }
    fun cancelLookup() { generation++; request?.cancel(); request = null; loading = false }
    fun closePanel() { cancelLookup(); selection = null; lastTap = null; wordMode = false }
    fun lookup(picked: Selection) {
        cancelLookup(); selection = picked; wordMode = true; wordResult = null; wordError = null; lastTap = null; loading = true
        saved = false; saveMessage = null
        val token = generation
        request = scope.launch {
            try {
                val chapter = book.chapters[picked.chapterIndex]
                val result = repository.lookup(WordQuery.from(picked), WordOrigin(book.id, book.title, chapter.id, chapter.title))
                if (generation == token) wordResult = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) wordError = e.message ?: "查词暂未完成，请重试。" }
            finally { if (generation == token) loading = false }
        }
    }
    LaunchedEffect(wordResult?.record?.id, notebookOpen) {
        val id = wordResult?.record?.id
        try { saved = if (id == null) false else withContext(Dispatchers.IO) { notebook.isSaved(id) } }
        catch (_: Exception) { saved = false; saveMessage = "生词本暂时无法读取，原数据已保留。" }
    }
    val activity = context as? MainActivity
    val wakeEnabled = trackingEnabled && !contents && !searchOpen && !notebookOpen && editing == null && aiQuestion == null && annotation == null
    val wakeAi by rememberUpdatedState<() -> Unit> {
        closePanel(); chrome = false; aiRanges = emptyList(); aiPreview = emptyList(); selectingAi = true
    }
    DisposableEffect(activity, wakeEnabled) {
        if (wakeEnabled) activity?.readingAiTrigger = { wakeAi() }
        onDispose { activity?.readingAiTrigger = null }
    }
    val window = (context as? Activity)?.window
    DisposableEffect(window) {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) } }
    }
    BackHandler { when { selectingAi -> { selectingAi = false; aiPreview = emptyList(); aiRanges = emptyList() }; selection != null -> closePanel(); !chrome -> chrome = true; else -> onBack() } }
    // Hidden system bars must not reserve empty strips. Transient bars overlay the page.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wordHeight = minOf(360.dp, maxHeight * .48f)
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val translation = selection?.alignment?.chinese.orEmpty()
        val translationStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 17.sp, lineHeight = 29.sp))
        val translationTextHeight = remember(translation, maxWidth, density, translationStyle) {
            with(density) {
                textMeasurer.measure(AnnotatedString(translation), style = translationStyle,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = (maxWidth - 44.dp).roundToPx().coerceAtLeast(1)))
                    .size.height.toDp()
            }
        }
        val translationHeight = minOf(maxHeight * .55f, maxOf(80.dp, translationTextHeight + 32.dp))
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
            ContinuousParagraphs(book, rows, list, store, settings, initial, selection, wordMode, Modifier.fillMaxSize(), jump,
                onJumpDone = { jump = null }, onChapter = { chapterIndex = it }, onProgress = { chapterProgress = it }, onChrome = { chrome = it }, onBlank = { closePanel(); chrome = true },
                onSelect = { picked ->
                    cancelLookup()
                    val now = SystemClock.uptimeMillis()
                    val identity = picked.chapterIndex to picked.range
                    val double = lastTap?.let { it.first == identity && now - it.second <= doubleTapTimeout } == true
                    selection = if (double) null else if (wordMode) picked else toggleSelection(selection, picked)
                    wordMode = false; lastTap = if (double) null else identity to now
                }, onWord = { lookup(it) }, onAnnotation = { p, index -> annotation = p to index }, selectingAi = selectingAi, aiRanges = aiRanges + aiPreview,
                onAiPreview = { aiPreview = it }, onAiPicked = { ranges ->
                    try {
                        val question = ReadingQuestions.selection(book, aiRanges + ranges)
                        aiRanges = question.passages; aiQuestion = question; selectingAi = false; aiPreview = emptyList()
                    } catch (e: Exception) { aiError = e.message }
                }, onAiCancel = { selectingAi = false; aiPreview = emptyList(); aiRanges = emptyList() },
                searchHit = searchHit, searchJump = searchJump, onSearchJumpDone = { searchJump = null })
            // Overlay instead of inserting/removing a row above the list: no text jumps on title changes.
            if (chrome && !selectingAi) Surface(modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
                color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 72.dp).testTag("reader-toolbar").padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GlyphButton("back", if (book.isArticle) "返回" else "返回书架", onBack)
                    if (book.isArticle) Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(book.title, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(book.section, book.author).filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(book.countLabel, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(book.title, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${book.chapters[chapterIndex].title}  ·  ${book.chapters[chapterIndex].coverage()}", fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("全书 ${book.countLabel} · 本章 ${book.chapters[chapterIndex].countLabel}", fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (book.isArticle) TextButton(onClick = { closePanel(); askArticle(ReadingQuestions.wholeArticle(book)) },
                        modifier = Modifier.testTag("ask-article")) { Text("问 AI") }
                    GlyphButton("search", if (book.isArticle) "搜索全文" else "搜索本书", { closePanel(); searchOpen = true })
                    if (!book.isArticle) GlyphButton("list", "目录", { contents = true })
                    if (book.sourceFormat == "pdf") TextButton(onClick = { pdfOriginal = true }) { Text("原页") }
                    GlyphButton("type", "阅读设置", onSettings)
                }
            }
            val progressColor = MaterialTheme.colorScheme.onBackground
            Canvas(Modifier.fillMaxWidth().height(4.dp).align(androidx.compose.ui.Alignment.TopCenter)
                .testTag("chapter-progress").semantics {
                    contentDescription = "${book.chapters[chapterIndex].title} 阅读进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(chapterProgress, 0f..1f)
                }) {
                drawRect(progressColor.copy(alpha = .14f))
                drawRect(progressColor.copy(alpha = .85f), size = androidx.compose.ui.geometry.Size(size.width * chapterProgress, size.height))
            }
            }
            selection?.let { s -> ReadingPanel(s, wordMode, wordResult, loading, wordError,
                if (wordMode) wordHeight else translationHeight,
                saved, saving, saveMessage,
                onSave = {
                    wordResult?.record?.let { record -> scope.launch {
                        saving = true
                        try {
                            withContext(Dispatchers.IO) { notebook.save(record) }
                            if (wordResult?.record?.id == record.id) { saved = true; saveMessage = "已保存原句、释义和来源。" }
                        } catch (_: Exception) { saveMessage = "保存失败，请重试。" } finally { saving = false }
                    } }
                }, onNotebook = { notebookOpen = true },
                onRetry = { lookup(s) }, onSettings = { chrome = true; onSettings() },
                onClose = ::closePanel,
                onAsk = if (!book.isArticle || s.alignment != null) null else { initial ->
                    val paragraph = book.chapters[s.chapterIndex].paragraphs.indexOfFirst { it.id == s.paragraph.id }
                    runCatching { ReadingQuestions.selection(book, listOf(PassageRange(s.chapterIndex, paragraph, s.range.start, s.range.end))) }
                        .onSuccess { closePanel(); askArticle(it, initial) }.onFailure { aiError = it.message }
                }) }
        }
    }
    annotation?.let { (paragraph, index) -> AnnotationDialog(paragraph, index, onDismiss = { annotation = null }) }
    aiQuestion?.let { question -> ReadingQuestionSheet(question,
        initialHistory = remember(question) { if (question.wholeArticle) chats.read(book.id) else emptyList() },
        onHistory = { history -> if (question.wholeArticle) scope.launch(Dispatchers.IO) { runCatching { if (history.isEmpty()) chats.clear(book.id) else chats.write(book.id, history) } } },
        initialQuestion = aiInitial,
        onDismiss = { aiQuestion = null; aiRanges = emptyList(); aiInitial = null },
        onReselect = { aiQuestion = null; aiRanges = emptyList(); aiPreview = emptyList(); selectingAi = true },
        onAppend = { aiQuestion = null; aiPreview = emptyList(); selectingAi = true }) }
    aiError?.let { message -> AlertDialog(onDismissRequest = { aiError = null }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { aiError = null }) { Text("好的") } }) }
    if (notebookOpen) WordNotebookDialog { notebookOpen = false }
    // A fixed dialog has one scroll owner. Pulling past the first row cannot drag/settle a sheet.
    if (contents) Dialog(onDismissRequest = { contents = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).safeDrawingPadding().padding(horizontal = 12.dp).testTag("chapter-dialog"),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("目录", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    GlyphButton("close", "关闭目录", { contents = false })
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("chapter-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    // TranslationFileActions and its import tools remain implemented; the directory hides their entry points.
                    val entries = book.contents.ifEmpty { book.chapters.mapIndexed { i, c -> ContentsEntry(c.title, i) } }
                    itemsIndexed(entries) { _, entry ->
                        ListItem(headlineContent = { Text(entry.title, maxLines = 2) },
                            modifier = Modifier.padding(start = (entry.depth.coerceIn(0, 6) * 16).dp).clickable {
                                closePanel(); searchHit = null
                                searchJump = ReadingPosition(entry.chapter, entry.paragraph, entry.character)
                                chapterIndex = entry.chapter; contents = false; chrome = true
                            })
                    }
                }
            }
        }
    }
    if (searchOpen) ReaderSearch(book, rows, onDismiss = { searchOpen = false }, onPick = { hit ->
        searchHit = hit; searchJump = ReadingPosition(hit.chapter, hit.paragraph, hit.start)
        chapterIndex = hit.chapter; searchOpen = false; chrome = true
    })
    editing?.let { (ci, ranges) -> TranslationEditor(book, ci, ranges, onDismiss = { editing = null }, onSave = { updated ->
        onUpdate(updated)
        selection = selection?.let { it.copy(alignment = updated.chapters[it.chapterIndex].translationAt(it.paragraph.id, it.range.start)) }
        editing = null
    }) }
}

@Composable
private fun ContinuousParagraphs(book: Book, rows: List<ReadingRow>, list: LazyListState, store: LibraryStore, settings: ReaderSettings,
    initial: ReadingPosition, selection: Selection?, wordMode: Boolean, modifier: Modifier, jump: Int?, onJumpDone: () -> Unit,
    onChapter: (Int) -> Unit, onProgress: (Float) -> Unit, onChrome: (Boolean) -> Unit, onBlank: () -> Unit, onSelect: (Selection) -> Unit, onWord: (Selection) -> Unit,
    onAnnotation: (Paragraph, Int) -> Unit,
    selectingAi: Boolean, aiRanges: List<PassageRange>, onAiPreview: (List<PassageRange>) -> Unit,
    onAiPicked: (List<PassageRange>) -> Unit, onAiCancel: () -> Unit,
    searchHit: PassageRange?, searchJump: ReadingPosition?, onSearchJumpDone: () -> Unit) {
    val passageLayouts = remember { mutableStateMapOf<String, PassageLayout>() }
    val passageBounds = remember { mutableStateMapOf<String, Rect>() }
    val layouts = remember { mutableStateMapOf<String, TextLayoutResult>() }
    val lifecycle = LocalLifecycleOwner.current
    var restoring by remember { mutableStateOf(true) }
    var anchor by remember { mutableStateOf(initial) }
    val latestChapter by rememberUpdatedState(onChapter)
    val latestProgress by rememberUpdatedState(onProgress)
    val chapterOffsets = remember(book.chapters) { book.chapters.map { chapter ->
        chapter.paragraphs.runningFold(0) { count, p -> count + if (p.heading) 0 else p.text.length }
    } }
    fun progress(position: ReadingPosition): Float {
        val chapter = book.chapters[position.chapter]
        val offsets = chapterOffsets[position.chapter]
        val paragraph = position.paragraph.coerceIn(chapter.paragraphs.indices)
        val character = if (chapter.paragraphs[paragraph].heading) 0 else position.character.coerceIn(0, chapter.paragraphs[paragraph].text.length)
        return ((offsets[paragraph] + character).toFloat() / offsets.last().coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    LaunchedEffect(book.chapters) { latestProgress(progress(initial)) }
    val latestChrome by rememberUpdatedState(onChrome)
    val toolbarInset = with(LocalDensity.current) { (88.dp + WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()).roundToPx() }
    val latestBlank by rememberUpdatedState(onBlank)
    fun currentPosition(): ReadingPosition {
        val row = rows[list.firstVisibleItemIndex.coerceIn(rows.indices)]
        val layout = layouts[row.key]
        val line = layout?.getLineForVerticalPosition(list.firstVisibleItemScrollOffset.toFloat()) ?: 0
        return ReadingPosition(row.chapter, row.paragraphIndex, layout?.getLineStart(line) ?: 0)
    }
    fun persist() { if (!restoring) { anchor = currentPosition(); store.savePosition(book, anchor) } }
    DisposableEffect(lifecycle, book.id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) persist() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { persist(); lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settings.size, settings.spacing, settings.serif) {
        restoring = true
        val index = rows.indexOfFirst { it.chapter == anchor.chapter && it.paragraphIndex == anchor.paragraph }.coerceAtLeast(0)
        val row = rows[index]
        snapshotFlow { layouts[row.key] }.collect { layout ->
            val expected = if (row.paragraph.heading) settings.size + 5 else settings.size
            if (restoring && layout != null && kotlin.math.abs(layout.layoutInput.style.fontSize.value - expected) < .1f &&
                kotlin.math.abs(layout.layoutInput.style.lineHeight.value - settings.size * settings.spacing) < .1f) {
                val line = layout.getLineForOffset(anchor.character.coerceIn(0, row.paragraph.text.length))
                list.scrollToItem(index, layout.getLineTop(line).toInt()); restoring = false
            }
        }
    }
    LaunchedEffect(jump) {
        if (jump != null) {
            restoring = true
            val index = rows.indexOfFirst { it.chapter == jump }.coerceAtLeast(0)
            list.scrollToItem(index); restoring = false; persist(); latestChapter(jump); onJumpDone()
        }
    }
    LaunchedEffect(searchJump) {
        val target = searchJump ?: return@LaunchedEffect
        restoring = true
        try {
            val index = rows.indexOfFirst { it.chapter == target.chapter && it.paragraphIndex == target.paragraph }
            if (index >= 0) {
                list.scrollToItem(index)
                val layout = snapshotFlow { layouts[rows[index].key] }.filterNotNull().first()
                val line = layout.getLineForOffset(target.character)
                list.scrollToItem(index, layout.getLineTop(line).toInt() - toolbarInset)
                latestChapter(target.chapter); latestChrome(true)
            }
        } finally { restoring = false }
        persist(); onSearchJumpDone()
    }
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
            .distinctUntilChanged().collect {
                if (!restoring) {
                    val position = currentPosition()
                    latestChapter(position.chapter)
                    latestProgress(if (!list.canScrollForward && position.chapter == book.chapters.lastIndex) 1f else progress(position))
                }
                if (!it.third) persist()
            }
    }
    // Track only real drags, not layout/bring-into-view scrolls caused by opening a panel.
    var dragging by remember { mutableStateOf(false) }
    var dragTravel by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { dragging = true; dragTravel = 0f }
                is DragInteraction.Stop -> {
                    dragging = false
                    if (dragTravel < -threshold) latestChrome(false)
                    else if (dragTravel > threshold) latestChrome(true)
                    dragTravel = 0f
                }
                is DragInteraction.Cancel -> { dragging = false; dragTravel = 0f }
            }
        }
    }
    val scroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (dragging && source == NestedScrollSource.UserInput) dragTravel += consumed.y
                return Offset.Zero
            }
        }
    }
    Box(modifier) {
    LazyColumn(state = list, userScrollEnabled = !selectingAi, modifier = Modifier.fillMaxSize().testTag("reading-body").nestedScroll(scroll)
        .pointerInput(Unit) { detectTapGestures(onTap = { latestBlank() }) },
        contentPadding = PaddingValues(start = 26.dp, end = 26.dp,
            top = 88.dp + WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(), bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(rows, key = { it.key }) { row ->
            val p = row.paragraph
            if (p.pdfPage != null) {
                PdfPageImage(book.id, p.pdfPage)
                return@items
            }
            val chapter = book.chapters[row.chapter]

            val active = selection?.takeIf { it.chapterIndex == row.chapter }
            val highlight = (active?.let {
                if (wordMode) listOf(SourceRange(it.paragraph.id, it.wordStart, it.wordStart + it.word.orEmpty().length))
                else it.alignment?.ranges ?: listOf(it.range)
            }.orEmpty().filter { it.paragraph == p.id } + aiRanges.filter { it.chapter == row.chapter && it.paragraph == row.paragraphIndex }.map { SourceRange(p.id, it.start, it.end) })
            val annotated = AnnotatedString.Builder(p.text).apply {
                p.marks.forEach { m -> addStyle(if (m.kind == "italic") SpanStyle(fontStyle = FontStyle.Italic) else SpanStyle(fontWeight = FontWeight.Bold), m.start, m.end) }
                p.annotations.forEach { note -> addStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline), note.start, note.end) }
                searchHit?.takeIf { it.chapter == row.chapter && it.paragraph == row.paragraphIndex }?.let {
                    addStyle(SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer), it.start, it.end)
                }
                highlight.forEach { addStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer), it.start, it.end) }
            }.toAnnotatedString()
            val selectAt: (Int, Boolean) -> Unit = { offset, longPress ->
                val sentence = TextLocator.readingUnitAt(p, offset)
                val word = TextLocator.wordAt(p.text, offset) ?: TextLocator.nearestWord(p.text, offset, sentence.first, sentence.last + 1)
                if (chapter.isChinese || (word == null && longPress)) latestChrome(true) else {
                    val lookupOffset = word?.first ?: offset.coerceIn(sentence.first, sentence.last)
                    val picked = Selection(p, SourceRange(p.id, sentence.first, sentence.last + 1), word?.let { p.text.substring(it) }, chapter.translationAt(p.id, lookupOffset),
                        p.notes.firstOrNull { lookupOffset >= it.start && lookupOffset < it.end }, row.chapter, lookupOffset)
                    if (longPress) { onWord(picked) } else onSelect(picked)
                }
            }
            val latestSelect by rememberUpdatedState(selectAt)
            DisposableEffect(row.key) { onDispose { layouts.remove(row.key); passageLayouts.remove(row.key); passageBounds.remove(row.key) } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(annotated, fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
                fontSize = (if (p.heading) settings.size + 5 else settings.size).sp,
                lineHeight = (settings.size * settings.spacing).sp, fontWeight = if (p.heading) FontWeight.SemiBold else FontWeight.Normal,
                onTextLayout = { layout -> layouts[row.key] = layout; passageBounds[row.key]?.let { bounds -> passageLayouts[row.key] = PassageLayout(row, bounds, layout) } }, modifier = Modifier.fillMaxWidth().testTag("paragraph-${row.chapter}-${row.paragraphIndex}")
                    .then(if (p.heading && row.chapter > 0) Modifier.padding(top = 36.dp) else Modifier)
                    .onGloballyPositioned { coords ->
                        val bounds = Rect(coords.positionInRoot(), androidx.compose.ui.geometry.Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
                        passageBounds[row.key] = bounds
                        layouts[row.key]?.let { passageLayouts[row.key] = PassageLayout(row, bounds, it) }
                    }
                    .semantics { customActions = listOf(CustomAccessibilityAction("查看本段开头译文") {
                        latestSelect(p.text.indexOfFirst { it.isLetter() }.coerceAtLeast(0), false); true
                    }) }
                    .pointerInput(row.key) {
                        fun hit(point: Offset, long: Boolean) {
                            val layout = layouts[row.key] ?: return
                            val line = layout.getLineForVerticalPosition(point.y)
                            // Accept the whole line band, including word spaces, punctuation and line-height leading.
                            // Trailing whitespace closes the panel without selecting another sentence.
                            if (point.x < layout.getLineLeft(line) || point.x > layout.getLineRight(line)) { latestBlank(); return }
                            val caret = layout.getOffsetForPosition(point)
                            val glyph = listOf(caret, caret - 1).firstOrNull { at -> at in p.text.indices &&
                                layout.getBoundingBox(at).let { box -> point.x >= box.left && point.x < box.right } }
                            val offset = (glyph ?: caret).coerceIn(layout.getLineStart(line), (layout.getLineEnd(line, visibleEnd = true) - 1).coerceAtLeast(layout.getLineStart(line))).coerceIn(0, p.text.lastIndex)
                            val noteIndex = p.annotations.indexOfFirst { offset >= it.start && offset < it.end }
                            if (!long && noteIndex >= 0 && !selectingAi) onAnnotation(p, noteIndex)
                            else latestSelect(offset, long)
                        }
                        detectTapGestures(onTap = { hit(it, false) }, onLongPress = { hit(it, true) })
                    })
            // Separate 48 dp controls keep every note reachable without covering the text.
            if (p.annotations.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.annotations.forEachIndexed { index, note ->
                    val quote = p.text.substring(note.start, note.end).replace('\n', ' ').trim()
                    OutlinedButton(onClick = { onAnnotation(p, index) }, enabled = !selectingAi,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 240.dp)
                            .testTag("annotation-${row.chapter}-${row.paragraphIndex}-$index"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("${index + 1} · $quote", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            }
        }
    }
    if (selectingAi) PassageOverlay(passageLayouts, rows, onAiPreview, onAiPicked, onAiCancel)
    }
}




