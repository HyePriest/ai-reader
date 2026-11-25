@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jknl.englishreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ReadingPanel(selection: Selection, wordMode: Boolean, result: WordResult?, loading: Boolean, error: String?,
    height: Dp, saved: Boolean, saving: Boolean, saveMessage: String?, onSave: () -> Unit, onNotebook: () -> Unit,
    onRetry: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit, onAsk: ((String?) -> Unit)? = null) {
    Surface(Modifier.testTag("translation-panel"), color = MaterialTheme.colorScheme.surface, shadowElevation = 5.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().height(height).padding(horizontal = 22.dp, vertical = 8.dp)) {
            if (wordMode) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(selection.word.orEmpty(), modifier = Modifier.weight(1f),
                    fontFamily = com.jknl.englishreader.ui.theme.ReadingSerif,
                    fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (!loading && result != null) TextButton(enabled = !saved && !saving, onClick = onSave) { Text(if (saved) "已加入" else if (saving) "保存中…" else "加入生词本", fontSize = 12.sp) }
                GlyphButton("book", "打开生词本", onNotebook)
                GlyphButton("close", "关闭译文", onClose)
            }
            key(selection.chapterIndex, selection.range, wordMode, selection.word, result) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!wordMode && onAsk != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val compact = PaddingValues(horizontal = 12.dp)
                        Button(onClick = { onAsk("翻译这句，并说明难点") }, contentPadding = compact, modifier = Modifier.testTag("translate-sentence")) { Text("翻译这句", fontSize = 13.sp, maxLines = 1) }
                        OutlinedButton(onClick = { onAsk("分析这句的句子结构") }, contentPadding = compact) { Text("拆解", fontSize = 13.sp, maxLines = 1) }
                        OutlinedButton(onClick = { onAsk(null) }, contentPadding = compact) { Text("问 AI", fontSize = 13.sp, maxLines = 1) }
                        Spacer(Modifier.weight(1f))
                        GlyphButton("close", "关闭", onClose)
                    }
                    else if (!wordMode) Text(selection.alignment?.chinese ?: "这里还没有译文。", fontSize = 17.sp, lineHeight = 29.sp)
                    else when {
                        loading -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在结合整句查词…", style = MaterialTheme.typography.bodyMedium)
                        }
                        error != null -> {
                            Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton(onClick = onRetry) { Text("重试") }
                                TextButton(onClick = onSettings) { Text("设置 API Key") }
                            }
                            selection.note?.let { Text("随书注释 · ${it.meaning}", fontSize = 14.sp, lineHeight = 22.sp) }
                        }
                        result != null -> {
                            ExplanationContent(result.explanation)
                            if (saveMessage != null) Text(saveMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (wordMode) Text("${result?.record?.goal?.title ?: "AI"} · 学习建议${if (result?.cached == true) " · 本地缓存" else " · DeepSeek"}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}


@Composable
internal fun ExplanationContent(r: WordExplanation) {
    Text("${r.lemma}  ·  ${r.pos}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    Text(r.contextMeaning, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
    Text(r.contextExplanation, fontSize = 14.sp, lineHeight = 23.sp)
    if (r.phrase.isNotBlank()) Text(r.phrase, fontSize = 14.sp, lineHeight = 22.sp)
    Advice("这个词", r.wordAdvice)
    Advice("这里的意思", r.senseAdvice)
    HorizontalDivider()
    Text("主要词义", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    r.senses.forEach { s ->
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${s.pos}  ${s.meaning}", fontSize = 15.sp, lineHeight = 24.sp)
            if (s.usage.isNotBlank()) Text(s.usage, fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (r.uncertainty.isNotBlank()) Text(r.uncertainty, fontSize = 12.sp, lineHeight = 20.sp)
}

@Composable private fun Advice(label: String, advice: StudyAdvice) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label · ${StudyLevels.display(advice.decision)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text(advice.reason, fontSize = 14.sp, lineHeight = 23.sp)
    }
}

@Composable
internal fun DeepSeekSettingsCard() {
    val context = LocalContext.current
    val keys = remember { ApiKeyStore(context) }
    val config = remember { DeepSeekSettings(context) }
    var configured by remember { mutableStateOf(keys.exists()) }
    // Never save key input in a saved-instance-state Bundle or echo an existing key.
    var input by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(config.model) }
    val models = listOf("deepseek-flash", "deepseek-v4-pro")
    var modelMenu by remember { mutableStateOf(false) }
    var customModel by remember { mutableStateOf(config.model !in models) }
    var goal by remember { mutableStateOf(config.goal) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HorizontalDivider()
    Text("DeepSeek", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Text("长按英文查词；按住音量减约 0.3 秒进入框选提问，不震动。选文提问在点「发送」后联网，短按查看已有译文不联网。", fontSize = 12.sp, lineHeight = 20.sp)
    Text("记忆筛选目标", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LearningGoal.entries.forEach { target -> FilterChip(selected = goal == target, onClick = { goal = target; config.goal = target }, label = { Text(target.title) }) }
    }
    Text("只判断「需要背 / 不用背」，分别评估这个词和本句义项。需要以后认出来也算需要背，并说明具体背哪些义项或搭配；不用背就是查完继续读，不要求下次记得。", fontSize = 12.sp, lineHeight = 20.sp)
    OutlinedTextField(input, { input = it }, label = { Text(if (configured) "已保存密钥 · 填写可替换" else "API Key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("api-key-input"))
    Text("查词与提问模型", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth().testTag("model-selector")) {
            Text(if (customModel) "自定义模型 · $model ▾" else "$model ▾")
        }
        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
            models.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = {
                model = choice; config.model = choice; customModel = false; modelMenu = false
                message = "已切换模型，下一次查词或提问生效。"
            }) }
            DropdownMenuItem(text = { Text("自定义模型") }, onClick = { customModel = true; modelMenu = false })
        }
    }
    if (customModel) OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("点选模型立即保存；自定义模型需填写准确 ID 后点保存。关闭思考模式以缩短等待。密钥加密保存在本机。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !saving && model.isNotBlank() && (input.isNotBlank() || configured), onClick = {
            scope.launch {
                saving = true
                try {
                    require(model.trim().matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "模型名称格式无效" }
                    withContext(Dispatchers.IO) { if (input.isNotBlank()) keys.save(input); config.model = model.trim() }
                    input = ""; configured = keys.exists(); message = "已保存，返回正文长按单词即可查词。"
                } catch (e: Exception) { message = if (e is IllegalArgumentException) e.message else "密钥保存失败，请重试。" }
                finally { saving = false }
            }
        }) { Text(if (saving) "保存中…" else "保存 AI 设置") }
        if (configured) TextButton(enabled = !saving, onClick = { keys.clear(); input = ""; configured = false; message = "密钥已清除。" }) { Text("清除密钥") }
    }
    if (message != null) Text(message!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}

