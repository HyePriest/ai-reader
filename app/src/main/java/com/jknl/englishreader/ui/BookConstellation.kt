package com.jknl.englishreader.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.ShelfBook
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@Composable
internal fun rememberStarlight(): State<Float> {
    val motion = rememberInfiniteTransition(label = "starlight")
    // Read this state only inside draw blocks: the list and text do not recompose every frame.
    return motion.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart), label = "star-phase")
}

private data class DistantStar(val x: Float, val y: Float, val depth: Float, val radius: Float, val phase: Float)

@Composable
internal fun LivingStarfield(motion: State<Float>, travel: State<Float>) {
    val stars = remember {
        val random = Random(7319)
        List(105) { DistantStar(random.nextFloat(), random.nextFloat(), .04f + random.nextFloat() * .15f,
            .35f + random.nextFloat() * .9f, random.nextFloat() * 6.28f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF030811), Color(0xFF0A1526), Color(0xFF060D1B))))
        val haze = Offset(size.width * .82f, size.height * .42f)
        drawCircle(Brush.radialGradient(listOf(Color(0x223C648A), Color(0x10243956), Color.Transparent),
            haze, size.height * .65f), size.height * .65f, haze)
        val violet = Offset(size.width * .05f, size.height * .82f)
        drawCircle(Brush.radialGradient(listOf(Color(0x1C65557F), Color.Transparent), violet, size.width * .9f),
            size.width * .9f, violet)
        val phase = motion.value
        stars.forEach { star ->
            val y = ((star.y * size.height + travel.value * star.depth) % size.height + size.height) % size.height
            val center = Offset(star.x * size.width, y)
            val alpha = .28f + (sin(phase + star.phase) + 1f) * .18f
            val tint = if (star.depth > .14f) Color(0xFFDAD1EE) else Color(0xFFB5D8EE)
            drawCircle(tint.copy(alpha = alpha), star.radius.dp.toPx(), center)
            if (star.radius > 1.1f) drawCircle(tint.copy(alpha = alpha * .09f), 4.dp.toPx(), center)
        }
    }
}

/** Non-overlapping touch regions, staggered in space; only visible clusters are composed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookConstellation(books: List<ShelfBook>, progress: Map<String, Float>, motion: State<Float>,
    selectedId: String?, onSelect: (ShelfBook) -> Unit, onRead: (ShelfBook) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val nodeHeight = 100.dp + 70.dp * fontScale
    val step = nodeHeight + 28.dp
    val topOffsets = books.indices.map { i -> step * (i / 2) + if (i % 2 == 1) (64 + (i / 2 % 2) * 17).dp else 0.dp }
    BoxWithConstraints(Modifier.fillMaxWidth().height((topOffsets.maxOrNull() ?: 0.dp) + nodeHeight + 16.dp)) {
        val nodeWidth = minOf(180.dp, (maxWidth - 28.dp) / 2)
        val density = LocalDensity.current
        val leftOffsets = books.indices.map { i ->
            val drift = ((books[i].id.hashCode().ushr(4) % 7)).dp
            if (i % 2 == 0) 7.dp + drift else maxWidth - nodeWidth - 7.dp - drift
        }
        val points = books.indices.map { i -> with(density) {
            Offset((leftOffsets[i] + nodeWidth / 2).toPx(), (topOffsets[i] + 48.dp).toPx())
        } }
        Canvas(Modifier.matchParentSize()) {
            points.zipWithNext().forEach { (from, to) ->
                // Leave the corona clear rather than drawing a line through the star itself.
                val vector = to - from
                val distance = vector.getDistance()
                if (distance > 76.dp.toPx()) {
                    val inset = vector * (36.dp.toPx() / distance)
                    drawLine(Color(0x267FABC7), from + inset, to - inset, .65.dp.toPx())
                }
            }
        }
        books.forEachIndexed { i, book ->
            val seed = book.id.hashCode().ushr(1)
            val tint = orbitColors[seed % orbitColors.size]
            val fraction = progress[book.id] ?: 0f
            val selected = selectedId == book.id
            Column(Modifier.offset(leftOffsets[i], topOffsets[i]).width(nodeWidth).height(nodeHeight)
                .testTag("book-${book.id}")
                .semantics { contentDescription = "${book.title}，已读 ${(fraction * 100).roundToInt()}%，轻点查看，长按阅读" }
                .combinedClickable(role = Role.Button, onClickLabel = "查看书籍", onLongClickLabel = "直接阅读",
                    onClick = { onSelect(book) }, onLongClick = { onRead(book) }),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    val center = Offset(size.width / 2, 48.dp.toPx())
                    val pulse = (sin(motion.value + (seed % 628) / 100f) + 1f) / 2f
                    val radius = (if (selected) 49f else 39f + pulse * 5f).dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .32f + pulse * .1f),
                        tint.copy(alpha = .055f), Color.Transparent), center, radius), radius, center)
                    val orbit = 21.dp.toPx()
                    drawCircle(tint.copy(alpha = if (selected) .42f else .16f), orbit, center, style = Stroke(.7.dp.toPx()))
                    if (fraction > 0f) drawArc(tint.copy(alpha = .8f), -90f, 360f * fraction, false,
                        center - Offset(orbit, orbit), Size(orbit * 2, orbit * 2), style = Stroke(1.4.dp.toPx()))
                    val ray = (12f + pulse * 4f).dp.toPx()
                    drawLine(tint.copy(alpha = .65f), center - Offset(ray, 0f), center + Offset(ray, 0f), .7.dp.toPx())
                    drawLine(tint.copy(alpha = .85f), center - Offset(0f, ray * 1.35f), center + Offset(0f, ray * 1.35f), .8.dp.toPx())
                    drawCircle(tint.copy(alpha = .45f), (5f + pulse).dp.toPx(), center)
                    drawCircle(Color(0xFFFFF7E5), (2.6f + pulse * .55f).dp.toPx(), center)
                    drawCircle(Color.White, 1.2.dp.toPx(), center)
                }
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 21.sp,
                    color = Starlight, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 5.dp))
                if (fraction > 0f) Text("已读 ${(fraction * 100).roundToInt()}%",
                    fontSize = 10.sp, color = tint.copy(alpha = .75f), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
