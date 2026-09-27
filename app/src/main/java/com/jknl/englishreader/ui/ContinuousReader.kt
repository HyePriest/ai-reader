@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jknl.englishreader.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
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
    var contents by rememberSaveable { mutableStateOf(false) }
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
    ReadingSession(book.id, trackingEnabled && !contents && !notebookOpen && editing == null)
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
    val wakeEnabled = trackingEnabled && !contents && !notebookOpen && editing == null && aiQuestion == null
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
        val translationHeight = minOf(maxHeight * .55f, maxOf(180.dp, translationTextHeight + 96.dp))
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
            ContinuousParagraphs(book, rows, list, store, settings, initial, selection, wordMode, Modifier.fillMaxSize(), jump,
                onJumpDone = { jump = null }, onChapter = { chapterIndex = it }, onChrome = { chrome = it },
                onSelect = { picked ->
                    cancelLookup()
                    val now = SystemClock.uptimeMillis()
                    val identity = picked.chapterIndex to picked.range
                    val double = lastTap?.let { it.first == identity && now - it.second <= doubleTapTimeout } == true
                    selection = if (double) null else if (wordMode) picked else toggleSelection(selection, picked)
                    wordMode = false; lastTap = if (double) null else identity to now
                }, onWord = { lookup(it) }, selectingAi = selectingAi, aiRanges = aiRanges + aiPreview,
                onAiPreview = { aiPreview = it }, onAiPicked = { ranges ->
                    try {
                        val question = ReadingQuestions.selection(book, aiRanges + ranges)
                        aiRanges = question.passages; aiQuestion = question; selectingAi = false; aiPreview = emptyList()
                    } catch (e: Exception) { aiError = e.message }
                }, onAiCancel = { selectingAi = false; aiPreview = emptyList(); aiRanges = emptyList() })
            // Overlay instead of inserting/removing a row above the list: no text jumps on title changes.
            if (chrome && !selectingAi) Surface(modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
                color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 72.dp).testTag("reader-toolbar").padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GlyphButton("back", "返回书架", onBack)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(book.title, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${chapterIndex + 1} / ${book.chapters.size}  ·  ${book.chapters[chapterIndex].coverage()}", fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("全书 ${book.countLabel} · 本章 ${book.chapters[chapterIndex].countLabel}", fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlyphButton("list", "目录", { contents = true })
                    GlyphButton("type", "阅读设置", onSettings)
                }
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
                onEdit = { editing = s.chapterIndex to (s.alignment?.ranges ?: listOf(s.range)) }, onClose = ::closePanel) }
        }
    }
    aiQuestion?.let { question -> ReadingQuestionSheet(question,
        onDismiss = { aiQuestion = null; aiRanges = emptyList() },
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
                    itemsIndexed(book.chapters, key = { _, c -> "chapter:${c.id}" }) { i, c ->
                        ListItem(headlineContent = { Text(c.title, maxLines = 2) }, supportingContent = { Text(c.countLabel) },
                            leadingContent = { Text("%02d".format(i + 1), color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { closePanel(); jump = i; chapterIndex = i; contents = false; chrome = true })
                    }
                }
            }
        }
    }
    editing?.let { (ci, ranges) -> TranslationEditor(book, ci, ranges, onDismiss = { editing = null }, onSave = { updated ->
        onUpdate(updated)
        selection = selection?.let { it.copy(alignment = updated.chapters[it.chapterIndex].translationAt(it.paragraph.id, it.range.start)) }
        editing = null
    }) }
}

@Composable
private fun ContinuousParagraphs(book: Book, rows: List<ReadingRow>, list: LazyListState, store: LibraryStore, settings: ReaderSettings,
    initial: ReadingPosition, selection: Selection?, wordMode: Boolean, modifier: Modifier, jump: Int?, onJumpDone: () -> Unit,
    onChapter: (Int) -> Unit, onChrome: (Boolean) -> Unit, onSelect: (Selection) -> Unit, onWord: (Selection) -> Unit,
    selectingAi: Boolean, aiRanges: List<PassageRange>, onAiPreview: (List<PassageRange>) -> Unit,
    onAiPicked: (List<PassageRange>) -> Unit, onAiCancel: () -> Unit) {
    val passageLayouts = remember { mutableStateMapOf<String, PassageLayout>() }
    val layouts = remember { mutableStateMapOf<String, TextLayoutResult>() }
    val lifecycle = LocalLifecycleOwner.current
    var restoring by remember { mutableStateOf(true) }
    var anchor by remember { mutableStateOf(initial) }
    val latestChapter by rememberUpdatedState(onChapter)
    val latestChrome by rememberUpdatedState(onChrome)
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
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
            .distinctUntilChanged().collect {
                if (!restoring) latestChapter(rows[it.first.coerceIn(rows.indices)].chapter)
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
        .pointerInput(Unit) { detectTapGestures(onTap = { latestChrome(true) }) },
        contentPadding = PaddingValues(start = 26.dp, end = 26.dp,
            top = 88.dp + WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(), bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(rows, key = { it.key }) { row ->
            val p = row.paragraph
            val chapter = book.chapters[row.chapter]

            val active = selection?.takeIf { it.chapterIndex == row.chapter }
            val highlight = (active?.let {
                if (wordMode) listOf(SourceRange(it.paragraph.id, it.wordStart, it.wordStart + it.word.orEmpty().length))
                else it.alignment?.ranges ?: listOf(it.range)
            }.orEmpty().filter { it.paragraph == p.id } + aiRanges.filter { it.chapter == row.chapter && it.paragraph == row.paragraphIndex }.map { SourceRange(p.id, it.start, it.end) })
            val annotated = AnnotatedString.Builder(p.text).apply {
                p.marks.forEach { m -> addStyle(if (m.kind == "italic") SpanStyle(fontStyle = FontStyle.Italic) else SpanStyle(fontWeight = FontWeight.Bold), m.start, m.end) }
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
            DisposableEffect(row.key) { onDispose { layouts.remove(row.key); passageLayouts.remove(row.key) } }
            Text(annotated, fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
                fontSize = (if (p.heading) settings.size + 5 else settings.size).sp,
                lineHeight = (settings.size * settings.spacing).sp, fontWeight = if (p.heading) FontWeight.SemiBold else FontWeight.Normal,
                onTextLayout = { layouts[row.key] = it; passageLayouts[row.key]?.let { old -> passageLayouts[row.key] = old.copy(layout = it) } }, modifier = Modifier.fillMaxWidth().testTag("paragraph-${row.chapter}-${row.paragraphIndex}")
                    .then(if (p.heading && row.chapter > 0) Modifier.padding(top = 36.dp) else Modifier)
                    .onGloballyPositioned { coords -> layouts[row.key]?.let { passageLayouts[row.key] = PassageLayout(row, Rect(coords.positionInRoot(), androidx.compose.ui.geometry.Size(coords.size.width.toFloat(), coords.size.height.toFloat())), it) } }
                    .semantics { customActions = listOf(CustomAccessibilityAction("查看本段开头译文") {
                        latestSelect(p.text.indexOfFirst { it.isLetter() }.coerceAtLeast(0), false); true
                    }) }
                    .pointerInput(row.key) {
                        fun hit(point: Offset, long: Boolean) {
                            val layout = layouts[row.key] ?: return
                            val line = layout.getLineForVerticalPosition(point.y)
                            // Accept the whole line band, including word spaces, punctuation and line-height leading.
                            // Only far-away trailing whitespace is reserved for revealing the toolbar.
                            val tolerance = 24.dp.toPx()
                            if (point.x < layout.getLineLeft(line) - tolerance || point.x > layout.getLineRight(line) + tolerance) { latestChrome(true); return }
                            val offset = layout.getOffsetForPosition(point).coerceIn(layout.getLineStart(line), (layout.getLineEnd(line, visibleEnd = true) - 1).coerceAtLeast(layout.getLineStart(line))).coerceIn(0, p.text.lastIndex)
                            latestSelect(offset, long)
                        }
                        detectTapGestures(onTap = { hit(it, false) }, onLongPress = { hit(it, true) })
                    })
        }
    }
    if (selectingAi) PassageOverlay(passageLayouts, rows, onAiPreview, onAiPicked, onAiCancel)
    }
}




