package com.jknl.englishreader.data

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Extracts local markup without executing scripts or loading external resources. */
internal object HtmlChapterReader {
    private val skipped = setOf("script", "style", "head", "nav", "noscript", "rt", "rp")
    private val containers = setOf("body", "div", "section", "article", "main", "header", "footer", "ul", "ol", "table", "tbody", "thead", "tr")
    private val blocks = containers + setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "td", "th", "figure", "figcaption", "dl", "dt", "dd")

    fun read(body: Element, id: String, fallbackTitle: String,
        noteText: (Element) -> String? = { null }, onAnchors: (Map<String, SourceRange>) -> Unit = {}): Chapter {
        val paragraphs = mutableListOf<Paragraph>()
        val anchors = mutableMapOf<String, SourceRange>()
        val pending = mutableListOf<Pair<List<String>, String>>()

        fun add(nodes: List<Node>, owner: Element?, heading: Boolean = false, pre: Boolean = false) {
            val text = StringBuilder()
            val marks = mutableListOf<Mark>()
            val localAnchors = mutableListOf<Pair<String, IntRange>>()
            val annotations = mutableListOf<BookAnnotation>()
            fun inline(node: Node) {
                if (node is TextNode) {
                    node.wholeText.forEach { c ->
                        if (!pre && (c.isWhitespace() || c == '\u00a0')) {
                            if (text.isNotEmpty() && text.last() !in " \n") text.append(' ')
                        } else text.append(c)
                    }
                    return
                }
                val e = node as? Element ?: return
                val tag = e.normalName()
                if (tag in skipped || e.hasAttr("hidden")) return
                require(!e.hasAttr("data-er-for")) { "中文译文标记必须放在独立段落中" }
                if (tag == "br") { text.append('\n'); return }
                val start = text.length
                e.childNodes().forEach(::inline)
                val note = if (tag == "a") noteText(e) else null
                if (note != null && text.length == start) text.append("〔注〕")
                val end = text.length
                if (end > start) {
                    if (tag in setOf("em", "i")) marks += Mark(start, end, "italic")
                    if (tag in setOf("strong", "b")) marks += Mark(start, end, "bold")
                    if (note != null) annotations += BookAnnotation(start, end, note)
                    val anchor = e.attr("data-er-id").ifEmpty { e.id() }
                    if (anchor.isNotBlank()) localAnchors += anchor to (start until end)
                    if (e.attr("name").isNotBlank()) localAnchors += e.attr("name") to (start until end)
                } else if (e.id().isNotBlank() || e.attr("name").isNotBlank()) {
                    localAnchors += e.id().ifBlank { e.attr("name") } to (start until start + 1)
                }
            }
            nodes.forEach(::inline)
            val value = text.toString().trimEnd()
            if (value.isBlank()) return
            val paragraphId = "p${paragraphs.size}"
            paragraphs += Paragraph(paragraphId, value, heading,
                marks.map { it.copy(end = it.end.coerceAtMost(value.length)) }.filter { it.start < it.end },
                annotations = annotations.map { it.copy(end = it.end.coerceAtMost(value.length)) }.filter { it.start < it.end })
            val ownerAnchor = owner?.attr("data-er-id")?.ifEmpty { owner.id() }.orEmpty()
            if (ownerAnchor.isNotBlank()) localAnchors += ownerAnchor to (0 until value.length)
            localAnchors.distinctBy { it.first }.forEach { (anchor, range) ->
                val end = (range.last + 1).coerceAtMost(value.length)
                require(!anchors.containsKey(anchor)) { "章节 $fallbackTitle 中的标记 $anchor 跨段落或重复" }
                if (range.first < end) anchors[anchor] = SourceRange(paragraphId, range.first, end)
            }
        }

        fun visit(e: Element) {
            val firstParagraph = paragraphs.size
            val tag = e.normalName()
            if (tag in skipped || e.hasAttr("hidden")) return
            val targets = e.attr("data-er-for")
            if (targets.isNotBlank()) {
                val chinese = e.text().trim()
                require(chinese.isNotBlank()) { "章节 $fallbackTitle 有空中文译文" }
                pending += targets.trim().split(Regex("\\s+")) to chinese
                return
            }
            val hasBlocks = e.getAllElements().any { it !== e && it.normalName() in blocks }
            if (tag !in containers && !hasBlocks) {
                add(e.childNodes(), e, tag.matches(Regex("h[1-6]")), tag == "pre")
                return
            }
            val run = mutableListOf<Node>()
            fun flush() { if (run.isNotEmpty()) { add(run.toList(), if (hasBlocks) null else e); run.clear() } }
            e.childNodes().forEach { child ->
                val element = child as? Element
                if (element != null && (element.normalName() in blocks || element.hasAttr("data-er-for") ||
                        element.getAllElements().any { it.normalName() in blocks })) {
                    flush()
                    visit(element)
                } else run += child
            }
            flush()
            val containerAnchor = e.attr("data-er-id").ifBlank { e.id() }
            if (containerAnchor.isNotBlank() && containerAnchor !in anchors && paragraphs.size > firstParagraph) {
                val p = paragraphs[firstParagraph]
                anchors[containerAnchor] = SourceRange(p.id, 0, p.text.length)
            }
        }
        visit(body)
        onAnchors(anchors)
        val alignments = pending.mapIndexed { index, (targets, chinese) ->
            Alignment("epub-$index", targets.map { requireNotNull(anchors[it]) { "中文找不到原文标记：$it" } }, chinese, "双语 EPUB")
        }
        return Chapter(id, paragraphs.firstOrNull { it.heading }?.text ?: fallbackTitle, paragraphs, alignments)
            .also(Alignments::validate)
    }
}
