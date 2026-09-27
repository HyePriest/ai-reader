package com.jknl.englishreader.data

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest

internal fun contentId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }.take(24)

/** Reads data from the user's existing HTML readers without running JavaScript or fetching URLs. */
class HtmlBookImporter {
    fun read(html: String): Book {
        val document = Jsoup.parse(html)
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val translations = jsonConstant(scripts, "TRANSLATIONS")
        val boundaries = jsonConstant(scripts, "SENTENCE_BOUNDS")
        val notes = jsonConstant(scripts, "NOTES", optional = true)
        val sections = document.select("section.chapter")
        require(sections.isNotEmpty()) { "这个 HTML 不是已支持的双语阅读格式。请使用阅读练习目录中的交互阅读版，或简洁书籍 JSON。" }
        val allIds = mutableSetOf<String>()
        val usedTranslations = mutableSetOf<String>()
        val chapters = sections.mapIndexed { index, section ->
            val heading = section.selectFirst(".chapter-head h2, h2")
            val title = heading?.text().orEmpty().ifBlank { "第 ${index + 1} 章" }
            val paragraphs = mutableListOf(Paragraph("@title", title, heading = true))
            val alignments = mutableListOf<Alignment>()
            heading?.attr("data-title-zh")?.takeIf { it.isNotBlank() }?.let { zh ->
                alignments += Alignment("@title", listOf(SourceRange("@title", 0, title.length)), zh, "HTML 章节标题")
            }
            for (block in section.select(".prose p[id], .prose pre[id]")) {
                val id = block.id()
                require(allIds.add(id)) { "HTML 中正文 ID 重复：$id" }
                val text = StringBuilder()
                val marks = mutableListOf<Mark>()
                val wordNotes = mutableListOf<WordNote>()
                fun walk(node: Node) {
                    if (node is TextNode) { text.append(node.wholeText); return }
                    val e = node as? Element ?: return
                    if (e.tagName() in setOf("script", "style")) return
                    val start = text.length
                    e.childNodes().forEach(::walk)
                    val end = text.length
                    if (end > start) {
                        if (e.tagName() in setOf("i", "em")) marks += Mark(start, end, "italic")
                        if (e.tagName() in setOf("b", "strong")) marks += Mark(start, end, "bold")
                        notes.optJSONObject(e.attr("data-note"))?.let { n ->
                            wordNotes += WordNote(start, end, n.optString("term", text.substring(start, end)), n.optString("meaning"))
                        }
                    }
                }
                walk(block)
                if (text.isBlank()) continue
                val bounds = boundaries.optJSONArray(id)
                val units = if (bounds != null) (0 until bounds.length()).map { i ->
                    val pair = bounds.getJSONArray(i)
                    require(pair.length() == 2) { "句子边界格式无效：$id" }
                    ReadingUnit(pair.getInt(0), pair.getInt(1))
                } else if (block.tagName() == "pre") listOf(ReadingUnit(0, text.length)) else emptyList()
                val p = Paragraph(id, text.toString(), marks = marks, units = units, notes = wordNotes)
                paragraphs += p
                translations.optJSONArray(id)?.let { zh ->
                    usedTranslations += id
                    require(zh.length() == units.size) { "$id 的译文数量与句子边界不一致，无法可靠导入" }
                    units.forEachIndexed { i, unit ->
                        if (!zh.isNull(i) && zh.getString(i).isNotBlank()) alignments += Alignment("$id:$i",
                            listOf(SourceRange(id, unit.start, unit.end)), zh.getString(i), "原 HTML 译文")
                    }
                }
            }
            require(paragraphs.size > 1) { "章节 $title 没有找到带 ID 的英文段落" }
            Chapter(section.id().ifBlank { "ch${index + 1}" }, title, paragraphs, alignments).also(Alignments::validate)
        }
        require(chapters.map { it.id }.distinct().size == chapters.size) { "章节 ID 重复" }
        require(translations.keys().asSequence().all { it in usedTranslations }) { "HTML 有中文找不到对应英文段落，未导入" }
        val title = document.title().substringBefore('｜').trim().ifBlank { "HTML 阅读书籍" }
        val author = document.selectFirst("meta[name=author]")?.attr("content")?.ifBlank { null } ?: "来自双语 HTML"
        // Visual style and optional offline dictionary scripts do not change the identity of the book.
        val fingerprint = chapters.joinToString("\u0000") { c -> c.id + "\u0000" + c.paragraphs.joinToString("\u0000") { it.id + "\u0000" + it.text } }
        return Book(contentId("html:$fingerprint"), title, author, chapters, System.currentTimeMillis())
    }

    private fun jsonConstant(script: String, name: String, optional: Boolean = false): JSONObject {
        val match = Regex("(?:const|let|var)\\s+$name\\s*=\\s*").find(script)
        if (match == null && optional) return JSONObject()
        require(match != null) { "HTML 缺少 $name，无法确认中英文对应关系" }
        val start = match.range.last + 1
        require(script.getOrNull(start) == '{') { "$name 必须是静态 JSON 数据" }
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until script.length) {
            val c = script[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return JSONObject(script.substring(start, i + 1)) }
            }
        }
        error("$name 数据不完整")
    }
}
