@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jknl.englishreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*

/** Answer the passage's questions, submit, then hand any question to the AI with the right context. */
@Composable
internal fun ExamSheet(book: Book, onDismiss: () -> Unit, onExplain: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val answers = remember { ExamAnswers(context) }
    var chosen by remember { mutableStateOf(answers.chosen(book.id)) }
    var submitted by remember { mutableStateOf(answers.submitted(book.id)) }
    val key = remember { answers.key(book.collection) }
    val questions = book.questions
    val right = questions.count { q -> key[q.number] != null && chosen[q.number] == key[q.number] }
    val keyed = questions.count { key[it.number] != null }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("exam-sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("做题 · ${book.title}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        !submitted -> "已答 ${chosen.count { (n, _) -> questions.any { it.number == n } }} / ${questions.size}"
                        keyed > 0 -> "答对 $right / $keyed" + if (keyed < questions.size) "（${questions.size - keyed} 题没有参考答案）" else ""
                        else -> "已交卷 · 没有参考答案，可让 AI 判断（可能有误），或在真题列表页填写答案"
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlyphButton("close", "关闭", onDismiss)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                items(questions, key = { it.number }) { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${q.number}. ${q.stem.ifBlank { "第 ${q.number} 空" }}", fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif, fontSize = 16.sp, lineHeight = 23.sp,
                            fontWeight = FontWeight.Medium)
                        q.options.forEachIndexed { i, option ->
                            val letter = q.label(i)
                            val mine = chosen[q.number] == letter
                            val correct = submitted && key[q.number] == letter
                            val wrong = submitted && mine && key[q.number] != null && key[q.number] != letter
                            val tint = when { correct -> Color(0xFF2E7D32); wrong -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
                            Row(Modifier.fillMaxWidth().clickable(enabled = !submitted) {
                                chosen = chosen + (q.number to letter); answers.choose(book.id, q.number, letter)
                            }.padding(vertical = 2.dp).testTag("option-${q.number}-$letter"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = mine, onClick = null, enabled = !submitted || mine)
                                Text("$letter. $option", color = tint, fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif, fontSize = 15.sp, lineHeight = 21.sp,
                                    fontWeight = if (correct) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        if (submitted) TextButton(onClick = { onExplain(ExamPrompts.one(q, chosen[q.number], key[q.number])) },
                            modifier = Modifier.testTag("explain-${q.number}")) { Text("讲解这题") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!submitted) Button(onClick = { submitted = true; answers.setSubmitted(book.id, true) }, modifier = Modifier.weight(1f).testTag("submit-exam"),
                    shape = RoundedCornerShape(14.dp)) { Text("交卷") }
                else {
                    OutlinedButton(onClick = { chosen = emptyMap(); submitted = false; answers.reset(book.id) }, modifier = Modifier.weight(1f)) { Text("重做") }
                    Button(onClick = { onExplain(ExamPrompts.all(questions, chosen, key)) }, modifier = Modifier.weight(1.4f).testTag("explain-all"),
                        shape = RoundedCornerShape(14.dp)) { Text("AI 讲解 + 整理生词") }
                }
            }
        }
    }
}

internal object ExamPrompts {
    private fun keyNote(key: Char?) = if (key != null) "参考答案是 $key" else "没有参考答案，请先给出你判断的答案和把握程度"
    fun one(q: ExamQuestion, mine: Char?, key: Char?) =
        "讲解第 ${q.number} 题。我选了 ${mine ?: "（未作答）"}，${keyNote(key)}。请定位原文依据（引用英文原句），说明正确项为什么对，我的选项和其他干扰项分别错在哪里。"
    fun all(questions: List<ExamQuestion>, chosen: Map<Int, Char>, key: Map<Int, Char>): String {
        val mine = questions.joinToString(" ") { "${it.number}${chosen[it.number] ?: "-"}" }
        val reference = questions.mapNotNull { q -> key[q.number]?.let { "${q.number}$it" } }
        return "我做完了这部分的题。我的答案：$mine。" +
            (if (reference.isNotEmpty()) "参考答案：${reference.joinToString(" ")}。" else "没有参考答案，请你先给出每题答案并说明把握程度。") +
            "请逐题对答案并讲解（定位原文依据、分析干扰项）。最后整理本文值得积累的生词、词组和句型，标注考研大纲外词、熟词僻义和一词多义。"
    }
}
