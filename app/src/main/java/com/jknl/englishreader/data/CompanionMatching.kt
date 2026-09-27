package com.jknl.englishreader.data

/** A bilingual JSON/HTML can supplement an existing EPUB without exporting any device IDs.
 * Paragraph text must match exactly apart from whitespace. Ambiguous matches are rejected. */
object CompanionMatching {
    private data class Normalized(val text: String, val positions: List<Int>)
    private fun normalize(text: String): Normalized {
        val out = StringBuilder()
        val positions = mutableListOf<Int>()
        text.forEachIndexed { index, c ->
            if (c.isWhitespace() || c == '\u00a0') {
                if (out.isNotEmpty() && out.last() != ' ') { out.append(' '); positions += index }
            } else { out.append(c); positions += index }
        }
        if (out.lastOrNull() == ' ') { out.deleteCharAt(out.lastIndex); positions.removeAt(positions.lastIndex) }
        return Normalized(out.toString(), positions)
    }

    fun plans(book: Book, document: ChineseDocument): List<ChapterPairing> {
        val source = requireNotNull(document.bilingualSource) { "文件没有英文定位信息" }
        val targetParagraphs = book.chapters.map { c -> c.paragraphs.filterNot { it.heading } }
        val targetTexts = targetParagraphs.map { ps -> ps.map { normalize(it.text) } }
        val result = mutableListOf<ChapterPairing>()
        for (sourceChapter in source.chapters) {
            val sourceParagraphs = sourceChapter.paragraphs.filterNot { it.heading }
            val ids = sourceParagraphs.map { it.id }.toSet()
            val translations = sourceChapter.alignments.filter { a -> a.ranges.all { it.paragraph in ids } }
            if (translations.isEmpty()) continue
            val normalized = sourceParagraphs.map { normalize(it.text) }
            fun match(targetIndex: Int): List<Paragraph>? {
                val paragraphs = targetParagraphs[targetIndex]
                val values = targetTexts[targetIndex].map { it.text }
                val early = mutableListOf<Int>()
                var next = 0
                for (item in normalized) {
                    val index = (next until values.size).firstOrNull { values[it] == item.text } ?: return null
                    early += index; next = index + 1
                }
                val late = mutableListOf<Int>()
                var previous = values.lastIndex
                for (item in normalized.reversed()) {
                    val index = (previous downTo 0).firstOrNull { values[it] == item.text } ?: return null
                    late += index; previous = index - 1
                }
                if (early != late.reversed()) return null
                return early.map { paragraphs[it] }
            }
            val candidates = book.chapters.mapIndexedNotNull { index, chapter -> match(index)?.let { Triple(index, chapter, it) } }
            val titled = candidates.filter { normalize(it.second.title).text == normalize(sourceChapter.title).text }
            val selected = (if (titled.isNotEmpty()) titled else candidates).singleOrNull()
            require(selected != null) { "《${sourceChapter.title}》的英文未能唯一定位。请保留 EPUB 原文、标点和段落；不要改写或跨段合并。" }
            require(result.none { it.chapterIndex == selected.first }) { "多个源章节定位到同一英文章节，请保留原章节结构" }
            val lookup = sourceParagraphs.indices.associate { sourceParagraphs[it].id to (normalized[it] to selected.third[it]) }
            val en = mutableListOf<ImportUnit>()
            val zh = mutableListOf<ImportUnit>()
            val pairs = mutableListOf<PairDraft>()
            translations.sortedWith(compareBy({ a -> sourceParagraphs.indexOfFirst { it.id == a.ranges.first().paragraph } }, { it.ranges.first().start })).forEach { a ->
                val begin = en.size
                a.ranges.forEach { range ->
                    val (original, target) = lookup.getValue(range.paragraph)
                    val targetNormalized = normalize(target.text)
                    val covered = original.positions.indices.filter { original.positions[it] >= range.start && original.positions[it] < range.end && original.text[it] != ' ' }
                    require(covered.isNotEmpty()) { "译文对应的英文为空" }
                    val start = targetNormalized.positions[covered.first()]
                    val end = targetNormalized.positions[covered.last()] + 1
                    en += ImportUnit(target.text.substring(start, end), SourceRange(target.id, start, end))
                }
                val z = zh.size
                zh += ImportUnit(a.chinese, source = a.source)
                pairs += PairDraft(begin, en.size, z, z + 1)
            }
            val chineseIndex = document.chapters.indexOfFirst { it.id == sourceChapter.id }
            require(chineseIndex >= 0) { "找不到译文章节" }
            result += ChapterPairing(selected.first, chineseIndex, en, zh, pairs, false)
        }
        require(result.isNotEmpty()) { "没有已填写的正文中文" }
        return result
    }
}

