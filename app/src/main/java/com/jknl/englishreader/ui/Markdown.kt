package com.jknl.englishreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The subset of Markdown that chat answers use. Parsing is pure so it can be unit-tested. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Item(val ordered: Boolean, val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

internal object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,3})[.)、]\\s*(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() { if (paragraph.isNotEmpty()) { blocks += MdBlock.Paragraph(paragraph.joinToString("\n").trim()); paragraph.clear() } }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                    blocks += MdBlock.Code(code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                rule.matches(trimmed) && paragraph.isEmpty() -> { flush(); blocks += MdBlock.Rule }
                heading.matches(trimmed) -> { flush(); heading.matchEntire(trimmed)!!.let { blocks += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2]) } }
                trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    blocks += MdBlock.Table(header, rows.map { r -> List(header.size) { r.getOrElse(it) { "" } } })
                    continue
                }
                trimmed.startsWith(">") -> { flush(); blocks += MdBlock.Quote(trimmed.trimStart('>').trim()) }
                bullet.matches(line) -> { flush(); bullet.matchEntire(line)!!.let { blocks += MdBlock.Item(false, "•", it.groupValues[1].length / 2, it.groupValues[2]) } }
                ordered.matches(line) && ordered.matchEntire(line)!!.groupValues[3].isNotBlank() -> {
                    flush(); ordered.matchEntire(line)!!.let { blocks += MdBlock.Item(true, it.groupValues[2] + ".", it.groupValues[1].length / 2, it.groupValues[3]) }
                }
                else -> paragraph += trimmed
            }
            i++
        }
        flush()
        // Merge consecutive quote lines into one block.
        return blocks.fold(mutableListOf()) { acc, b ->
            val last = acc.lastOrNull()
            if (b is MdBlock.Quote && last is MdBlock.Quote) acc[acc.lastIndex] = MdBlock.Quote(last.text + "\n" + b.text) else acc += b
            acc
        }
    }

    private fun cells(row: String) = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    /** **bold**, __bold__, *italic*, `code`, ~~strike~~. Unmatched markers stay as text. */
    fun inline(text: String, code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace)): AnnotatedString = buildAnnotatedString {
        var i = 0
        fun findClose(marker: String, from: Int): Int {
            val end = text.indexOf(marker, from)
            return if (end > from) end else -1
        }
        while (i < text.length) {
            val rest = text.substring(i)
            val marker = when {
                rest.startsWith("**") -> "**"
                rest.startsWith("__") -> "__"
                rest.startsWith("~~") -> "~~"
                rest.startsWith("`") -> "`"
                rest.startsWith("*") && rest.length > 1 && !rest[1].isWhitespace() -> "*"
                else -> null
            }
            val close = marker?.let { findClose(it, i + it.length) } ?: -1
            if (marker != null && close > 0) {
                val inner = text.substring(i + marker.length, close)
                val style = when (marker) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "`" -> code
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                pushStyle(style)
                if (marker == "`") append(inner) else append(inline(inner, code))
                pop()
                i = close + marker.length
            } else { append(text[i]); i++ }
        }
    }
}

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier, fontSize: Float = 15.5f) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (fontSize * .9f).sp,
        background = MaterialTheme.colorScheme.surfaceVariant)
    val line = fontSize * 1.65f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(MarkdownParser.inline(block.text, codeStyle), fontWeight = FontWeight.SemiBold,
                    fontSize = when (block.level) { 1 -> fontSize + 5; 2 -> fontSize + 3; else -> fontSize + 1 }.sp,
                    lineHeight = (fontSize * 1.5f + 4).sp, modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp))
                is MdBlock.Paragraph -> Text(MarkdownParser.inline(block.text, codeStyle), fontSize = fontSize.sp, lineHeight = line.sp)
                is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(block.marker, fontSize = fontSize.sp, lineHeight = line.sp, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp))
                    Text(MarkdownParser.inline(block.text, codeStyle), fontSize = fontSize.sp, lineHeight = line.sp, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = .5f), RoundedCornerShape(2.dp)))
                    Text(MarkdownParser.inline(block.text, codeStyle), fontSize = fontSize.sp, lineHeight = line.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                }
                is MdBlock.Code -> Text(block.text, fontFamily = FontFamily.Monospace, fontSize = (fontSize * .88f).sp, lineHeight = (fontSize * 1.4f).sp,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState()).padding(10.dp))
                is MdBlock.Table -> MarkdownTable(block, fontSize, codeStyle)
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, fontSize: Float, code: SpanStyle) {
    val border = MaterialTheme.colorScheme.outline
    val wide = table.header.size > 3
    Box(Modifier.fillMaxWidth().then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
        Column(Modifier.border(1.dp, border, RoundedCornerShape(8.dp))) {
            (listOf(table.header) + table.rows).forEachIndexed { r, row ->
                Row(Modifier.then(if (r == 0) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) else Modifier)
                    .height(IntrinsicSize.Min)) {
                    row.forEachIndexed { c, cell ->
                        val cellModifier = if (wide) Modifier.width(140.dp) else Modifier.weight(1f)
                        Text(MarkdownParser.inline(cell, code), fontSize = (fontSize * .9f).sp, lineHeight = (fontSize * 1.4f).sp,
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal, modifier = cellModifier.fillMaxHeight().padding(8.dp))
                        if (c < row.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(border))
                    }
                }
                if (r < table.rows.size) Box((if (wide) Modifier.width(141.dp * row.size) else Modifier.fillMaxWidth()).height(1.dp).background(border))
            }
        }
    }
}
