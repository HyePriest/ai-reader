package com.jknl.englishreader.data

import java.text.BreakIterator
import java.util.Locale

fun Int.formatWords(): String = String.format(Locale.US, "%,d", this)

data class Mark(val start: Int, val end: Int, val kind: String)
data class ReadingUnit(val start: Int, val end: Int)
data class WordNote(val start: Int, val end: Int, val term: String, val meaning: String)
data class BookAnnotation(val start: Int, val end: Int, val text: String, val title: String = "", val source: String = "")
data class Paragraph(val id: String, val text: String, val heading: Boolean = false, val marks: List<Mark> = emptyList(),
    val units: List<ReadingUnit> = emptyList(), val notes: List<WordNote> = emptyList(), val annotations: List<BookAnnotation> = emptyList(),
    val pdfPage: Int? = null)
data class SourceRange(val paragraph: String, val start: Int, val end: Int)
data class Alignment(val id: String, val ranges: List<SourceRange>, val chinese: String, val source: String)
data class Chapter(val id: String, val title: String, val paragraphs: List<Paragraph>, val alignments: List<Alignment>) {
    val wordCount: Int by lazy { paragraphs.filterNot { it.heading }.sumOf { TextLocator.wordCount(it.text) } }
    val isChinese: Boolean by lazy { alignments.isEmpty() && DocumentImporter.chinese(paragraphs.filterNot { it.heading }.take(20).joinToString("") { it.text.take(1000) }) }
    val characterCount: Int by lazy { paragraphs.filterNot { it.heading }.sumOf { TextLocator.hanCount(it.text) } }
    val countLabel: String get() = if (isChinese) "${characterCount.formatWords()} 字" else "${wordCount.formatWords()} 词"
    fun translationAt(paragraph: String, offset: Int) = alignments.firstOrNull { a ->
        a.ranges.any { it.paragraph == paragraph && offset >= it.start && offset < it.end }
    }
    fun coverage(): String = coverageLabel
    // A chapter is replaced when translations change. Do not scan all its alignments on every tap.
    private val coverageLabel: String by lazy {
        if (isChinese) return@lazy "中文正文"
        val text = paragraphs.filterNot { it.heading }
        val byParagraph = alignments.flatMap { it.ranges }.groupBy { it.paragraph }
        if (text.none { byParagraph[it.id]?.isNotEmpty() == true }) return@lazy "暂无中文"
        val complete = text.all { p ->
            val ranges = byParagraph[p.id].orEmpty().sortedBy { it.start }
            var end = 0
            var covered = true
            ranges.forEach { r ->
                if (p.text.substring(end.coerceAtMost(r.start), r.start).any { !it.isWhitespace() }) covered = false
                end = maxOf(end, r.end)
            }
            covered && p.text.substring(end).all { it.isWhitespace() }
        }
        if (complete && text.isNotEmpty()) "已配中文" else "部分配有中文"
    }
}
data class ContentsEntry(val title: String, val chapter: Int, val paragraph: Int = 0, val character: Int = 0, val depth: Int = 0)

data class Book(val id: String, val title: String, val author: String, val chapters: List<Chapter>, val importedAt: Long,
    val sourceFormat: String = "", val contents: List<ContentsEntry> = emptyList()) {
    val wordCount: Int by lazy { chapters.sumOf { it.wordCount } }
    val isChinese: Boolean by lazy { chapters.filter { c -> c.paragraphs.any { !it.heading } }.let { body -> body.isNotEmpty() && body.count { it.isChinese } > body.size / 2 } }
    val countLabel: String get() = if (isChinese) "${chapters.sumOf { it.characterCount }.formatWords()} 字" else "${wordCount.formatWords()} 词"
}
data class ReadingPosition(val chapter: Int = 0, val paragraph: Int = 0, val character: Int = 0)
data class ReaderSettings(val size: Float = 21f, val spacing: Float = 1.7f, val palette: String = "paper", val serif: Boolean = true)
data class Selection(val paragraph: Paragraph, val range: SourceRange, val word: String?, val alignment: Alignment?, val note: WordNote? = null,
    val chapterIndex: Int = 0, val wordStart: Int = range.start)

fun toggleSelection(current: Selection?, next: Selection): Selection? = if (current?.chapterIndex == next.chapterIndex && current.range == next.range) null else next

object TextLocator {
    fun hanCount(text: String): Int = text.codePoints().filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }.count().toInt()
    private val word = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")
    private val abbreviation = Regex("(?:\\b(?:Mr|Mrs|Ms|Dr|Prof|St|Sr|Jr|vs|etc)|\\b[A-Z])\\.$", RegexOption.IGNORE_CASE)

    fun wordAt(text: String, offset: Int): IntRange? = word.findAll(text).firstOrNull { offset in it.range }?.range
    fun wordCount(text: String): Int {
        // Counting needs no Kotlin MatchResult/range allocations for every word in a novel.
        val matcher = word.toPattern().matcher(text)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    fun nearestWord(text: String, offset: Int, start: Int = 0, end: Int = text.length): IntRange? =
        word.findAll(text).filter { it.range.first >= start && it.range.last < end }
            .minByOrNull { if (offset < it.range.first) it.range.first - offset else if (offset > it.range.last) offset - it.range.last else 0 }?.range

    fun readingUnitAt(paragraph: Paragraph, offset: Int): IntRange {
        val target = offset.coerceIn(0, paragraph.text.lastIndex)
        if (paragraph.units.isEmpty()) return sentenceAt(paragraph.text, target)
        val unit = paragraph.units.firstOrNull { target >= it.start && target < it.end }
            ?: paragraph.units.minBy { minOf(kotlin.math.abs(target - it.start), kotlin.math.abs(target - (it.end - 1))) }
        return unit.start until unit.end
    }

    fun sentenceAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        val target = offset.coerceIn(0, text.lastIndex)
        while (end != BreakIterator.DONE) {
            // Keep honorifics and initials attached to the following sentence fragment.
            if (abbreviation.containsMatchIn(text.substring(start, end).trimEnd()) && end < text.length) {
                end = iterator.next()
                continue
            }
            if (target < end) {
                while (start < end && text[start].isWhitespace()) start++
                while (end > start && text[end - 1].isWhitespace()) end--
                return start until end
            }
            start = end
            end = iterator.next()
        }
        return start until text.length
    }
}

object Alignments {
    fun validate(chapter: Chapter) {
        val paragraphs = chapter.paragraphs.associateBy { it.id }
        require(paragraphs.size == chapter.paragraphs.size) { "正文位置标记重复" }
        chapter.paragraphs.forEach { p ->
            require(p.text.isNotBlank()) { "正文段落不能为空" }
            p.units.forEach { require(it.start >= 0 && it.end > it.start && it.end <= p.text.length) { "句子边界超出正文：${p.id}" } }
            p.units.sortedBy { it.start }.zipWithNext().forEach { (a, b) -> require(a.end <= b.start) { "句子边界重叠：${p.id}" } }
            p.notes.forEach { require(it.start >= 0 && it.end > it.start && it.end <= p.text.length) { "注释超出正文：${p.id}" } }
            p.annotations.forEach {
                require(it.start >= 0 && it.end > it.start && it.end <= p.text.length) { "注释超出正文：${p.id}" }
                require(it.text.isNotBlank()) { "注释内容不能为空：${p.id}" }
            }
        }
        require(chapter.alignments.map { it.id }.distinct().size == chapter.alignments.size) { "译文标记重复" }
        chapter.alignments.forEach { a ->
            require(a.chinese.isNotBlank() && a.ranges.isNotEmpty()) { "译文或对应英文为空" }
            a.ranges.forEach { r ->
                val p = requireNotNull(paragraphs[r.paragraph]) { "译文指向不存在的英文" }
                require(r.start >= 0 && r.end > r.start && r.end <= p.text.length) { "中英文对应范围无效" }
            }
        }
        chapter.alignments.flatMap { it.ranges }.groupBy { it.paragraph }.values.forEach { ranges ->
            ranges.sortedBy { it.start }.zipWithNext().forEach { (a, b) ->
                require(a.end <= b.start) { "同一段英文关联了重叠译文，请先校正双语文件" }
            }
        }
    }

    fun overlaps(a: SourceRange, b: SourceRange) = a.paragraph == b.paragraph && a.start < b.end && b.start < a.end

    fun replacing(chapter: Chapter, replacement: Alignment): Chapter {
        val kept = chapter.alignments.filterNot { old -> old.ranges.any { a -> replacement.ranges.any { overlaps(a, it) } } }
        return chapter.copy(alignments = kept + replacement).also(::validate)
    }
}

