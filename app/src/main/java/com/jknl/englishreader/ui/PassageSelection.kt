package com.jknl.englishreader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jknl.englishreader.data.*
import kotlin.math.max
import kotlin.math.min

internal data class PassageLayout(val row: ReadingRow, val bounds: Rect, val layout: TextLayoutResult)

/** Hit-test line bands, then take a continuous source span in reading order, never a column of fragments. */
internal fun boxPassages(rect: Rect, layouts: List<PassageLayout>, rows: List<ReadingRow>): List<PassageRange> {
    val hits = mutableListOf<PassageRange>()
    layouts.sortedWith(compareBy<PassageLayout> { it.row.chapter }.thenBy { it.row.paragraphIndex }).forEach { p ->
        val l = p.layout
        for (line in 0 until l.lineCount) {
            val band = Rect(p.bounds.left + l.getLineLeft(line), p.bounds.top + l.getLineTop(line),
                p.bounds.left + l.getLineRight(line), p.bounds.top + l.getLineBottom(line))
            if (!rect.overlaps(band)) continue
            val y = (l.getLineTop(line) + l.getLineBottom(line)) / 2
            var start = l.getOffsetForPosition(Offset(max(rect.left, band.left) - p.bounds.left, y))
            var end = l.getOffsetForPosition(Offset(min(rect.right, band.right) - p.bounds.left, y))
            start = start.coerceIn(l.getLineStart(line), l.getLineEnd(line, true))
            end = end.coerceIn(start, l.getLineEnd(line, true))
            if (end == start && end < l.getLineEnd(line, true)) end++
            val text = p.row.paragraph.text
            if (start < text.length) TextLocator.wordAt(text, start)?.let { start = it.first }
            if (end > 0) TextLocator.wordAt(text, end - 1)?.let { end = it.last + 1 }
            if (start > 0 && start < text.length && text[start].isLowSurrogate()) start--
            if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end++
            if (end > start) hits += PassageRange(p.row.chapter, p.row.paragraphIndex, start, end)
        }
    }
    if (hits.isEmpty()) return emptyList()
    val first = hits.first(); val last = hits.last()
    val from = rows.indexOfFirst { it.chapter == first.chapter && it.paragraphIndex == first.paragraph }
    val to = rows.indexOfFirst { it.chapter == last.chapter && it.paragraphIndex == last.paragraph }
    return rows.subList(from, to + 1).mapIndexedNotNull { i, row ->
        val start = if (i == 0) first.start else 0
        val end = if (i == to - from) last.end else row.paragraph.text.length
        if (start < end) PassageRange(row.chapter, row.paragraphIndex, start, end) else null
    }
}

@Composable
internal fun PassageOverlay(layouts: Map<String, PassageLayout>, rows: List<ReadingRow>,
    onPreview: (List<PassageRange>) -> Unit, onPicked: (List<PassageRange>) -> Unit, onCancel: () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var picked by remember { mutableStateOf(emptyList<PassageRange>()) }
    var empty by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val latestPreview by rememberUpdatedState(onPreview)
    val latestPicked by rememberUpdatedState(onPicked)
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        Canvas(Modifier.fillMaxSize().testTag("ai-selection-overlay").pointerInput(Unit) {
            detectDragGestures(onDragStart = { point -> start = point; end = point; picked = emptyList(); empty = false },
                onDrag = { change, _ ->
                    change.consume(); end = change.position
                    val a = start ?: change.position; val b = change.position
                    val rect = Rect(min(a.x,b.x) + origin.x, min(a.y,b.y) + origin.y, max(a.x,b.x) + origin.x, max(a.y,b.y) + origin.y)
                    picked = boxPassages(rect, layouts.values.toList(), rows)
                    latestPreview(picked)
                }, onDragEnd = {
                    if (picked.isNotEmpty()) latestPicked(picked) else empty = true
                    start = null; end = null
                }, onDragCancel = { start = null; end = null; latestPreview(emptyList()) })
        }) {
            val a = start; val b = end
            if (a != null && b != null) {
                val left = min(a.x,b.x); val top = min(a.y,b.y)
                val size = androidx.compose.ui.geometry.Size(kotlin.math.abs(a.x-b.x), kotlin.math.abs(a.y-b.y))
                drawRect(color.copy(alpha = .08f), Offset(left,top), size)
                drawRect(color.copy(alpha = .7f), Offset(left,top), size, style = Stroke(1.5.dp.toPx()))
            }
        }
        Surface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 24.dp, start = 16.dp, end = 16.dp),
            shape = MaterialTheme.shapes.medium, tonalElevation = 5.dp) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (empty) "没有选中文字，请重新拖动" else "拖动框选文字，松手提问", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}
