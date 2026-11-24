package com.jknl.englishreader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import com.jknl.englishreader.ui.theme.InterfaceSans
import com.jknl.englishreader.ui.theme.ReadingSerif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One place for the reading type scale, so position restore and rendering always agree. */
internal data class ReadingStyle(val family: FontFamily, val size: Float, val line: Float, val weight: FontWeight) {
    companion object {
        /** About 65–75 characters per line on tablets; phones use the full width. */
        val measure = 640.dp
        fun body(settings: ReaderSettings) = if (settings.serif) ReadingSerif else InterfaceSans
        fun of(book: Book, p: Paragraph, settings: ReaderSettings): ReadingStyle {
            val family = body(settings)
            if (book.isArticle && p.heading && p.id == "@title") return headline(settings)
            return if (p.heading) ReadingStyle(family, settings.size + 7, (settings.size + 7) * 1.28f, FontWeight.SemiBold)
            else ReadingStyle(family, settings.size, settings.size * settings.spacing, FontWeight.Normal)
        }
        fun headline(settings: ReaderSettings) = ReadingStyle(body(settings), settings.size * 1.55f, settings.size * 1.55f * 1.2f, FontWeight.SemiBold)
    }
}

/** Kicker, headline, Chinese title, standfirst, byline and lead image at the top of an article. */
@Composable
internal fun ArticleHeader(book: Book, settings: ReaderSettings, modifier: Modifier = Modifier, onHeadlineLayout: (TextLayoutResult) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val headline = ReadingStyle.headline(settings)
    Column(modifier.testTag("article-header")) {
        val kicker = when (book.sourceFormat) {
            ExamPaper.FORMAT -> listOf(book.collection, book.section)
            else -> listOf(book.section.ifBlank { book.collection })
        }.filter { it.isNotBlank() }.joinToString("  ·  ")
        if (kicker.isNotBlank()) Text(kicker.uppercase(), fontFamily = InterfaceSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp))
        Text(book.title, fontFamily = headline.family, fontWeight = headline.weight, fontSize = headline.size.sp, lineHeight = headline.line.sp,
            style = TextStyle(lineBreak = LineBreak.Heading, hyphens = Hyphens.None), color = MaterialTheme.colorScheme.onBackground,
            onTextLayout = onHeadlineLayout)
        if (book.titleZh.isNotBlank()) Text(book.titleZh, fontSize = 17.sp, lineHeight = 25.sp, color = muted, modifier = Modifier.padding(top = 8.dp))
        if (book.subtitle.isNotBlank()) Text(book.subtitle, fontFamily = headline.family, fontStyle = FontStyle.Italic,
            fontSize = (settings.size * 1.02f).sp, lineHeight = (settings.size * 1.45f).sp, color = muted, modifier = Modifier.padding(top = 14.dp))
        val minutes = (book.wordCount / 200).coerceAtLeast(1)
        val byline = listOfNotNull(book.author.takeIf { it.isNotBlank() && book.sourceFormat != ExamPaper.FORMAT }, book.countLabel,
            if (book.isChinese) null else "约 $minutes 分钟").joinToString("  ·  ")
        Text(byline, fontFamily = InterfaceSans, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 18.dp))
        HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.outline)
        LeadImage(book.id, Modifier.padding(top = 20.dp))
    }
}

@Composable
private fun LeadImage(id: String, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val covers = remember { BookCovers(context) }
    val image by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { if (covers.file(id).exists()) covers.load(id)?.asImageBitmap() else null }
    }
    image?.let {
        val ratio = (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(1.2f, 2f)
        Image(it, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).testTag("lead-image"))
    }
}

/** End mark plus the next useful actions, so finishing an article leads somewhere. */
@Composable
internal fun ArticleFooter(book: Book, modifier: Modifier = Modifier, onAsk: (String?) -> Unit, onExam: () -> Unit) {
    Column(modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("■", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (book.questions.isNotEmpty()) "读完了，开始做题吧" else "读完了", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (book.questions.isNotEmpty()) Button(onClick = onExam, modifier = Modifier.fillMaxWidth().testTag("footer-exam")) { Text("做题 · ${book.questions.size} 道") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAsk("全文大意是什么？") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("全文大意", maxLines = 1) }
                    OutlinedButton(onClick = { onAsk("挑出最难的 3 个长难句并拆解") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("长难句", maxLines = 1) }
                    OutlinedButton(onClick = { onAsk(null) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("问 AI", maxLines = 1) }
                }
            }
        }
    }
}
