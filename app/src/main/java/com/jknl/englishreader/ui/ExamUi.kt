package com.jknl.englishreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.theme.ReadingSerif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Answers for one passage, shared by the docked panel and the cloze blanks in the text. */
@Stable
internal class ExamState(val book: Book, private val answers: ExamAnswers) {
    val questions = book.questions
    /** Cloze items have no stem; their blanks live in the passage. */
    val isCloze = questions.isNotEmpty() && questions.all { it.stem.isBlank() }
    var chosen by mutableStateOf(answers.chosen(book.id)); private set
    var submitted by mutableStateOf(answers.submitted(book.id)); private set
    var key: Map<Int, Char> by mutableStateOf(answers.key(book.collection)); private set
    var aiJudged: Set<Int> by mutableStateOf(answers.aiJudged(book.collection)); private set
    var uncertain: Set<Int> by mutableStateOf(answers.uncertain(book.collection)); private set
    var sources: List<AnswerSource> by mutableStateOf(answers.sources(book.collection)); private set
    fun apply(result: KeyResult) {
        answers.mergeKey(book.collection, result)
        key = answers.key(book.collection); aiJudged = answers.aiJudged(book.collection)
        uncertain = answers.uncertain(book.collection); sources = answers.sources(book.collection)
    }
    var current by mutableIntStateOf(0)
    val question get() = questions[current.coerceIn(questions.indices)]
    val answered get() = questions.count { chosen[it.number] != null }
    val right get() = questions.count { key[it.number] != null && chosen[it.number] == key[it.number] }
    val keyed get() = questions.count { key[it.number] != null }
    fun choose(number: Int, option: Char) { if (!submitted) { chosen = chosen + (number to option); answers.choose(book.id, number, option) } }
    fun submit() { submitted = true; answers.setSubmitted(book.id, true) }
    fun reset() { chosen = emptyMap(); submitted = false; current = 0; answers.reset(book.id) }
    fun goTo(number: Int) { questions.indexOfFirst { it.number == number }.takeIf { it >= 0 }?.let { current = it } }
    fun nextUnanswered(): Int? = (questions.indices.drop(current + 1) + questions.indices.take(current))
        .firstOrNull { chosen[questions[it].number] == null }
    /** null before submitting or without a key. */
    fun correct(number: Int): Boolean? = if (!submitted) null else key[number]?.let { chosen[number] == it }
    fun word(number: Int): String? = questions.firstOrNull { it.number == number }?.let { q -> chosen[number]?.let { q.options.getOrNull(it - 'A') } }
}

internal object ExamColors {
    @Composable fun good(): Color = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFF2E7D32) else Color(0xFF8FD19E)
}

/** Docked under the passage: the passage stays readable while you answer. */
@Composable
internal fun ExamPanel(state: ExamState, height: Dp, maxHeight: Dp, onExplain: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Drag the handle to trade passage space for question space.
    var panelHeight by remember(height) { mutableStateOf(height) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        panelHeight = (panelHeight - with(density) { delta.toDp() }).coerceIn(220.dp, maxHeight)
    }
    val q = state.question
    val strip = rememberLazyListState()
    LaunchedEffect(state.current) { strip.animateScrollToItem((state.current - 2).coerceAtLeast(0)) }
    Surface(Modifier.fillMaxWidth().height(panelHeight).testTag("exam-panel"), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 10.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(18.dp).draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical).testTag("exam-panel-handle"),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            }
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    !state.submitted -> "已答 ${state.answered}/${state.questions.size}"
                    state.keyed > 0 -> "答对 ${state.right}/${state.keyed}"
                    else -> "已交卷 · 无参考答案"
                }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (!state.submitted) TextButton(onClick = { state.submit() }, enabled = state.answered > 0, modifier = Modifier.testTag("submit-exam")) { Text("交卷") }
                else TextButton(onClick = { state.reset() }) { Text("重做") }
                GlyphButton("close", "收起答题", onClose)
            }
            if (state.submitted) Box(Modifier.padding(horizontal = 16.dp)) { Column { KeySourceLine(state) } }
            LazyRow(state = strip, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.questions, key = { _, it -> it.number }) { i, item -> NumberChip(state, i, item.number) }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (state.submitted && state.keyed < state.questions.size) KeyLookupCard(state)
                val judged = when { q.number in state.uncertain -> "（AI 判断，没把握）"; q.number in state.aiJudged -> "（AI 判断）"; else -> "" }
                val verdict = if (!state.submitted) null else state.key[q.number].let { key -> when {
                    key == null -> "还没有答案"
                    state.chosen[q.number] == key -> "答对了 · 答案 $key$judged"
                    else -> "答案 $key$judged · 你选了 ${state.chosen[q.number] ?: "（未答）"}"
                } }
                val verdictColor = if (state.correct(q.number) == true) ExamColors.good() else if (state.correct(q.number) == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                if (state.isCloze) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${q.number} 空", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    verdict?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = verdictColor) }
                }
                else Text("${q.number}. ${q.stem}", fontFamily = ReadingSerif, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                fun pick(letter: Char) {
                    state.choose(q.number, letter)
                    scope.launch { delay(260); state.nextUnanswered()?.let { state.current = it } }
                }
                if (state.isCloze) q.options.indices.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { i -> OptionButton(state, q, i, Modifier.weight(1f), compact = true) { pick(q.label(i)) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                } else q.options.indices.forEach { i -> OptionButton(state, q, i, Modifier.fillMaxWidth(), compact = false) { pick(q.label(i)) } }
                if (!state.isCloze) verdict?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = verdictColor) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { state.current = (state.current - 1).coerceAtLeast(0) }, enabled = state.current > 0,
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Text("上一题") }
                if (state.submitted) {
                    OutlinedButton(onClick = { onExplain(ExamPrompts.one(q, state.chosen[q.number], state.key[q.number], q.number in state.aiJudged)) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("讲解这题", maxLines = 1) }
                    Button(onClick = { onExplain(ExamPrompts.all(state.questions, state.chosen, state.key, state.aiJudged)) }, modifier = Modifier.weight(1.3f).testTag("explain-all"),
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("全部讲解+生词", maxLines = 1) }
                } else {
                    Spacer(Modifier.weight(1f))
                    val last = state.current == state.questions.lastIndex
                    if (last || state.answered == state.questions.size) Button(onClick = { state.submit() }, enabled = state.answered > 0) {
                        Text(if (state.answered < state.questions.size) "交卷（${state.questions.size - state.answered} 题未答）" else "交卷")
                    } else Button(onClick = { state.current++ }) { Text("下一题") }
                }
            }
        }
    }
}

@Composable
private fun NumberChip(state: ExamState, index: Int, number: Int) {
    val current = state.current == index
    val result = state.correct(number)
    val answered = state.chosen[number] != null
    val bg = when {
        result == true -> ExamColors.good().copy(alpha = .18f)
        result == false -> MaterialTheme.colorScheme.errorContainer
        answered -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when { result == true -> ExamColors.good(); result == false -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
    Box(Modifier.size(34.dp).background(bg, CircleShape)
        .then(if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
        .clickable { state.current = index }.testTag("chip-$number"), contentAlignment = Alignment.Center) {
        Text(if (number in state.uncertain && state.submitted) "$number?" else "$number", fontSize = 13.sp,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, color = fg)
    }
}

@Composable
private fun OptionButton(state: ExamState, q: ExamQuestion, index: Int, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    val letter = q.label(index)
    val mine = state.chosen[q.number] == letter
    val isKey = state.submitted && state.key[q.number] == letter
    val wrong = state.submitted && mine && state.key[q.number] != null && !isKey
    val good = ExamColors.good()
    val border = when { isKey -> good; wrong -> MaterialTheme.colorScheme.error; mine -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.outline }
    val bg = when { isKey -> good.copy(alpha = .12f); wrong -> MaterialTheme.colorScheme.errorContainer; mine -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surface }
    Surface(onClick = onClick, enabled = !state.submitted, shape = RoundedCornerShape(12.dp), color = bg, border = BorderStroke(if (mine || isKey) 1.5.dp else 1.dp, border),
        modifier = modifier.heightIn(min = 46.dp).testTag("option-${q.number}-$letter")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(if (mine || isKey) border else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Text("$letter", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (mine || isKey) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(q.options[index], fontFamily = ReadingSerif, fontSize = if (compact) 15.sp else 15.sp, lineHeight = 21.sp,
                maxLines = if (compact) 2 else 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** Finds the paper's answers online (DeepSeek web search), falling back to AI answers for this part. */
@Composable
private fun KeyLookupCard(state: ExamState) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val lookup = remember { AnswerLookup(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("key-lookup")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (busy) "正在联网查找「${state.book.collection}」的答案，并用题目核对卷子，通常要 1–2 分钟…"
                else "这部分还没有参考答案。可以让 DeepSeek 联网查官方答案；查不到时由 AI 作答并标出没把握的题。",
                fontSize = 13.sp, lineHeight = 19.sp)
            error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Button(onClick = {
                busy = true; error = null
                scope.launch {
                    try { state.apply(lookup.find(state.book)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "没有查到，请稍后重试" }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth().testTag("lookup-key")) { Text("联网查答案并判分") }
        }
    }
}

/** Where the answers came from: a web source to check, or a reminder that AI judged them. */
@Composable
internal fun KeySourceLine(state: ExamState) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val partAi = state.questions.count { it.number in state.aiJudged }
    if (state.sources.isNotEmpty() && partAi < state.questions.size) {
        val first = state.sources.first()
        Text("答案来源：" + state.sources.take(2).joinToString("、") { it.title.take(18) } + " · 点击核对", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { runCatching { uri.openUri(first.url) } }.padding(vertical = 2.dp))
    }
    if (partAi > 0) Text("有 $partAi 题的答案由 AI 判断，可能有误" + if (state.uncertain.any { n -> state.questions.any { it.number == n } }) "；带 ? 的题它没把握。" else "。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A cloze blank drawn inside the passage, showing the chosen word. */
@Composable
internal fun ClozeBlank(state: ExamState, number: Int, fontSize: Float, active: Boolean, onTap: () -> Unit) {
    val word = state.word(number)
    val result = state.correct(number)
    val good = ExamColors.good()
    val color = when { result == true -> good; result == false -> MaterialTheme.colorScheme.error; word != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Box(Modifier.fillMaxSize().padding(horizontal = 1.dp)
        .background(if (active) MaterialTheme.colorScheme.primaryContainer else color.copy(alpha = .08f), RoundedCornerShape(6.dp))
        .border(if (active) 1.5.dp else 1.dp, color.copy(alpha = if (active) 1f else .5f), RoundedCornerShape(6.dp))
        .clickable(onClick = onTap).testTag("blank-$number"), contentAlignment = Alignment.Center) {
        Text(word ?: "", fontFamily = ReadingSerif, fontSize = (fontSize * .88f).sp, color = color, maxLines = 1, fontWeight = FontWeight.Medium)
    }
}

internal object ClozeText {
    val blank = Regex("\\((\\d{1,2})\\)(_{2,})")
    /** Width of a blank, in em, sized to the chosen word so text does not jump much. */
    fun width(word: String?) = ((word?.length ?: 5).coerceAtLeast(5) * .52f + 1.1f)
}

internal object ExamPrompts {
    private fun keyNote(key: Char?, ai: Boolean) = when {
        key == null -> "没有参考答案，请先给出你判断的答案和把握程度"
        ai -> "之前 AI 判断的答案是 $key（不是官方答案），请重新独立判断，如果它错了请直接指出"
        else -> "参考答案是 $key"
    }
    fun one(q: ExamQuestion, mine: Char?, key: Char?, ai: Boolean = false) =
        (if (q.stem.isBlank()) "讲解完形填空第 ${q.number} 空。" else "讲解第 ${q.number} 题。") +
        "我选了 ${mine ?: "（未作答）"}，${keyNote(key, ai)}。请定位原文依据（引用英文原句），说明正确项为什么对，我的选项和其他干扰项分别错在哪里" +
        (if (q.stem.isBlank()) "；说明这一空考查的是词义辨析、固定搭配、逻辑关系还是语法，并给出这几个选项的常见搭配。" else "。")
    fun all(questions: List<ExamQuestion>, chosen: Map<Int, Char>, key: Map<Int, Char>, ai: Set<Int> = emptySet()): String {
        val mine = questions.joinToString(" ") { "${it.number}${chosen[it.number] ?: "-"}" }
        val reference = questions.mapNotNull { q -> key[q.number]?.let { "${q.number}$it" } }
        val cloze = questions.all { it.stem.isBlank() }
        return "我做完了这部分的题。我的答案：$mine。" +
            (if (reference.isNotEmpty()) "参考答案：${reference.joinToString(" ")}。" else "没有参考答案，请你先给出每题答案并说明把握程度。") +
            (questions.filter { it.number in ai && key[it.number] != null }.takeIf { it.isNotEmpty() }?.let { list ->
                "其中 ${list.joinToString("、") { "${it.number}" }} 题的参考答案是 AI 判断的，不是官方答案，请独立核对，错了直接指出。" } ?: "") +
            (if (cloze) "请逐空对答案，用表格列出：题号、正确答案、我的答案、考点（词义/搭配/逻辑/语法）、一句话理由；再重点讲我做错的空。"
            else "请逐题对答案并讲解（定位原文依据、分析干扰项）。") +
            "最后整理本文值得积累的生词、词组和句型，标注考研大纲外词、熟词僻义和一词多义。"
    }
}
