package com.jknl.englishreader.data

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ln

data class ChineseChapter(val id: String, val title: String, val paragraphs: List<String>)
data class ChineseDocument(val id: String, val title: String, val chapters: List<ChineseChapter>, val bilingualSource: Book? = null)
data class ImportUnit(val text: String, val range: SourceRange? = null, val source: String? = null)
/** End indices are exclusive. Empty sides represent unpaired material, never confirmed omissions. */
data class PairDraft(val enStart: Int, val enEnd: Int, val zhStart: Int, val zhEnd: Int, val selected: Boolean = true)
data class ChapterPairing(
    val chapterIndex: Int, val chineseIndex: Int,
    val english: List<ImportUnit>, val chinese: List<ImportUnit>, val pairs: List<PairDraft>,
    val paragraphMode: Boolean, val chineseClauses: Boolean = false
)

object ChineseImport {
    fun read(input: InputStream, name: String): ChineseDocument {
        val raw = BookFiles.readDocument(input, name)
        val chapters = raw.chapters.map { c ->
            val translations = c.alignments.filter { a -> a.ranges.any { r -> c.paragraphs.any { it.id == r.paragraph && !it.heading } } }
            ChineseChapter(c.id, c.title, if (translations.isNotEmpty()) translations.map { it.chinese }
                else c.paragraphs.filterNot { it.heading }.map { it.text })
        }.filter { it.paragraphs.isNotEmpty() }
        require(chapters.isNotEmpty() && chapters.any { c -> c.paragraphs.any(DocumentImporter::chinese) }) { "没有找到中文正文，请选择中文 EPUB、HTML、TXT 或已有双语书籍 JSON" }
        return ChineseDocument(raw.id, raw.title, chapters, raw.takeIf { b -> b.chapters.any { it.alignments.isNotEmpty() } })
    }

    fun englishUnits(chapter: Chapter, sentenceMode: Boolean): List<ImportUnit> = chapter.paragraphs.filterNot { it.heading }.flatMap { p ->
        val bounds = if (!sentenceMode) listOf(ReadingUnit(0, p.text.length))
            else if (p.units.size > 1) p.units else splitEnglish(p.text)
        bounds.map { ImportUnit(p.text.substring(it.start, it.end), SourceRange(p.id, it.start, it.end)) }
    }

    private fun splitEnglish(text: String): List<ReadingUnit> {
        val result = mutableListOf<ReadingUnit>()
        var offset = 0
        while (offset < text.length) {
            while (offset < text.length && text[offset].isWhitespace()) offset++
            if (offset >= text.length) break
            val range = TextLocator.sentenceAt(text, offset)
            require(!range.isEmpty() && range.last >= offset) { "英文分句未能前进，请使用按段配对" }
            result += ReadingUnit(range.first, range.last + 1)
            offset = range.last + 1
        }
        return result
    }

    fun chineseUnits(chapter: ChineseChapter, sentenceMode: Boolean, clauses: Boolean = false): List<ImportUnit> = chapter.paragraphs.flatMapIndexed { paragraphIndex, original ->
        val text = original + if (paragraphIndex < chapter.paragraphs.lastIndex) "\n\n" else ""
        if (!sentenceMode) listOf(ImportUnit(text)) else {
            // Include all punctuation and whitespace in the original slices, without rewriting text.
            val result = mutableListOf<ImportUnit>()
            val ends = Regex(if (clauses) "[。！？!?，,；;：:]+[”’\"」』）)]*" else "[。！？!?]+[”’\"」』）)]*").findAll(text).map { it.range.last + 1 }.toList()
            var start = 0
            ends.forEach { end -> result += ImportUnit(text.substring(start, end)); start = end }
            if (start < text.length) {
                val tail = text.substring(start)
                if (tail.isBlank() && result.isNotEmpty()) result[result.lastIndex] = ImportUnit(result.last().text + tail)
                else result += ImportUnit(tail)
            }
            result.ifEmpty { listOf(ImportUnit(text)) }
        }
    }

    /** Length/order proposals only; UI must explicitly require review before committing. */
    fun propose(book: Book, document: ChineseDocument, chapterIndex: Int, chineseIndex: Int, sentences: Boolean = true, clauses: Boolean = false): ChapterPairing {
        var en = englishUnits(book.chapters[chapterIndex], sentences)
        var zh = chineseUnits(document.chapters[chineseIndex], sentences, clauses)
        var paragraphs = !sentences
        if ((en.size + 1L) * (zh.size + 1L) > 1_500_000L) {
            en = englishUnits(book.chapters[chapterIndex], false)
            zh = chineseUnits(document.chapters[chineseIndex], false)
            paragraphs = true
        }
        require(en.isNotEmpty() && zh.isNotEmpty()) { "所选章节没有可配对的正文" }
        require((en.size + 1L) * (zh.size + 1L) <= 1_500_000L) { "章节过长，请先将源文件按章节拆分" }
        val n = en.size; val m = zh.size; val width = m + 1
        val cost = FloatArray((n + 1) * width) { Float.POSITIVE_INFINITY }
        val move = ByteArray(cost.size)
        fun prefix(units: List<ImportUnit>) = DoubleArray(units.size + 1).also { a -> units.forEachIndexed { i, u -> a[i + 1] = a[i] + u.text.count { it.isLetterOrDigit() }.coerceAtLeast(1) } }
        val es = prefix(en); val zs = prefix(zh)
        val ratio = zs.last() / es.last()
        cost[0] = 0f
        for (i in 0..n) for (j in 0..m) {
            val at = i * width + j
            if (!cost[at].isFinite()) continue
            for (a in 0..3) for (b in 0..3) {
                if (a + b == 0 || (a == 0 && b != 1) || (b == 0 && a != 1) || i + a > n || j + b > m) continue
                val penalty = if (a == 0 || b == 0) 2.8 else {
                    val expected = (es[i + a] - es[i]) * ratio
                    val actual = zs[j + b] - zs[j]
                    val mismatch = abs(ln((actual + 1) / (expected + 1)))
                    mismatch * 2.0 + (a + b - 2) * .42
                }
                val next = (i + a) * width + j + b
                val candidate = cost[at] + penalty.toFloat()
                if (candidate < cost[next]) { cost[next] = candidate; move[next] = (a * 4 + b).toByte() }
            }
        }
        var i = n; var j = m
        val pairs = mutableListOf<PairDraft>()
        while (i > 0 || j > 0) {
            val step = move[i * width + j].toInt()
            val a = step / 4; val b = step % 4
            check(a + b > 0)
            pairs += PairDraft(i - a, i, j - b, j, a > 0 && b > 0)
            i -= a; j -= b
        }
        return ChapterPairing(chapterIndex, chineseIndex, en, zh, pairs.reversed(), paragraphs, clauses && !paragraphs)
    }

    /** No mutation of original text/IDs. Entire overlapping old groups must be replaced, not partially erased. */
    fun prepare(book: Book, plans: List<ChapterPairing>, replace: Boolean): TranslationImport {
        require(plans.map { it.chapterIndex }.distinct().size == plans.size) { "同一英文章节不能重复导入" }
        plans.groupBy { it.chineseIndex }.values.forEach { shared ->
            require(shared.all { it.chinese == shared.first().chinese }) { "同一中文章分配给多个英文篇章时，请使用相同的切分方式" }
        }
        val sharedChinese = mutableSetOf<Pair<Int, Int>>()
        var count = 0; var conflicts = 0
        val updated = book.chapters.mapIndexed { ci, c ->
            val plan = plans.firstOrNull { it.chapterIndex == ci } ?: return@mapIndexed c
            val selected = plan.pairs.filter { it.selected }
            val usedEn = mutableSetOf<Int>(); val usedZh = mutableSetOf<Int>()
            val additions = selected.map { pair ->
                require(pair.enStart >= 0 && pair.enEnd in (pair.enStart + 1)..plan.english.size && pair.zhStart >= 0 && pair.zhEnd in (pair.zhStart + 1)..plan.chinese.size) { "存在空白或越界配对，请取消或修正该组" }
                require((pair.enStart until pair.enEnd).all { usedEn.add(it) } && (pair.zhStart until pair.zhEnd).all { usedZh.add(it) }) { "配对范围重复，请检查合并或修改的范围" }
                require((pair.zhStart until pair.zhEnd).all { sharedChinese.add(plan.chineseIndex to it) }) { "多个英文章节使用了同一段中文，请调整中文范围或取消重复项" }
                val ranges = plan.english.subList(pair.enStart, pair.enEnd).map { u ->
                    val r = requireNotNull(u.range)
                    val p = requireNotNull(c.paragraphs.find { it.id == r.paragraph })
                    require(p.text.substring(r.start, r.end) == u.text) { "正文已经变化，请重新配对" }
                    r
                }
                Alignment("file:${contentId(ranges.toString())}", ranges,
                    plan.chinese.subList(pair.zhStart, pair.zhEnd).joinToString("") { it.text },
                    plan.chinese.subList(pair.zhStart, pair.zhEnd).mapNotNull { it.source }.distinct().joinToString(" + ").ifBlank { "中文文件 · 用户确认" })
            }
            Alignments.validate(c.copy(alignments = additions))
            val accepted = if (replace) additions else additions.filter { a -> c.alignments.none { old -> touches(old, a) } }
            val old = c.alignments.filter { o -> accepted.any { touches(o, it) } }
            if (replace) old.forEach { o ->
                o.ranges.forEach { r ->
                    val paragraph = c.paragraphs.first { it.id == r.paragraph }
                    val cover = accepted.flatMap { it.ranges }.filter { it.paragraph == r.paragraph }.sortedBy { it.start }
                    var end = r.start
                    cover.forEach { n ->
                        if (n.end > end && n.start < r.end) {
                            require(n.start <= end || paragraph.text.substring(end, minOf(n.start, r.end)).isBlank()) { "替换只覆盖了已有译文的一部分，请选中完整范围或保留已有译文" }
                            end = maxOf(end, n.end)
                        }
                    }
                    require(end >= r.end || paragraph.text.substring(end, r.end).isBlank()) { "替换只覆盖了已有译文的一部分，请选中完整范围或保留已有译文" }
                }
            }
            count += accepted.size; conflicts += old.size
            // Store finer units only where an accepted alignment supplies the corresponding Chinese.
            val paragraphs = c.paragraphs.map { p ->
                val added = accepted.flatMap { it.ranges }.filter { it.paragraph == p.id }
                if (added.isEmpty()) p else {
                    val base = if (p.units.isNotEmpty()) p.units else splitEnglish(p.text)
                    val cuts = (base.flatMap { listOf(it.start, it.end) } + added.flatMap { listOf(it.start, it.end) } + listOf(0, p.text.length)).distinct().sorted()
                    p.copy(units = cuts.zipWithNext().filter { (s, e) -> p.text.substring(s, e).isNotBlank() }.map { (s, e) -> ReadingUnit(s, e) })
                }
            }
            c.copy(paragraphs = paragraphs, alignments = c.alignments - old.toSet() + accepted).also(Alignments::validate)
        }
        require(count > 0) { "没有可保存的新配对；已有译文默认保留，可检查选中项或明确选择替换" }
        return TranslationImport(book.copy(chapters = updated), count, conflicts, plans.map { book.chapters[it.chapterIndex].title })
    }

    private fun touches(a: Alignment, b: Alignment) = a.ranges.any { x -> b.ranges.any { Alignments.overlaps(x, it) } }
}

