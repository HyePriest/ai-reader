package com.jknl.englishreader.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * A single article is stored as a one-chapter Book, so reading, lookup, the notebook
 * and AI questions all work unchanged. Only the home screen treats it differently.
 */
object Articles {
    const val PASTED = "article"
    const val ECONOMIST = "economist"
    val formats = setOf(PASTED, ECONOMIST)
    const val MAX_TEXT = 200_000
    /** Whole articles fit into one AI request; longer pasted text is cut at this size. */
    const val MAX_AI_CONTEXT = 24_000

    private val terminal = Regex("[.!?;:…\"”’)\\]]$")

    /** Pasted or shared plain text. The first short line becomes the title when it looks like one. */
    fun fromText(raw: String, source: String = "", now: Long = System.currentTimeMillis()): Book {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').replace("​", "").trim()
        require(text.isNotBlank()) { "没有可阅读的文字" }
        require(text.length <= MAX_TEXT) { "文字超过 20 万字符，请作为书籍导入" }
        val blocks = paragraphs(text)
        require(blocks.isNotEmpty()) { "没有可阅读的文字" }
        val first = blocks.first()
        val titled = blocks.size > 1 && TextLocator.wordCount(first) in 1..20 && first.length <= 160 &&
            !first.trimEnd().let { it.endsWith('.') || it.endsWith(',') || it.endsWith(';') || it.endsWith(':') }
        val title = if (titled) first else autoTitle(first)
        val body = if (titled) blocks.drop(1) else blocks
        return build(title, body, now, PASTED, author = source.ifBlank { "粘贴的文章" }, source = source)
    }

    /** Readable text from a web page. Navigation, captions and footers are dropped. */
    fun fromHtml(html: String, url: String, now: Long = System.currentTimeMillis()): Book {
        val doc = Jsoup.parse(html, url)
        doc.select("script, style, noscript, nav, footer, header, aside, form, figure, figcaption, button, svg").remove()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifBlank { doc.title().trim() }
            .ifBlank { "网页文章" }
        val candidates = doc.select("article").ifEmpty { doc.select("main") }.ifEmpty { org.jsoup.select.Elements(doc.body()) }
        val container = candidates.maxByOrNull { c -> c.select("p").sumOf { TextLocator.wordCount(it.text()) } } ?: doc.body()
        val body = container.select("p").map { clean(it.text()) }.filter { TextLocator.wordCount(it) >= 6 }.distinct()
        require(body.sumOf { TextLocator.wordCount(it) } >= 120) { "没能从这个网页取出正文。可以在浏览器里复制正文，再粘贴进来。" }
        val host = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        return build(title, body, now, PASTED, author = host.ifBlank { "网页" }, source = url)
    }

    /** A short document imported as a file (TXT, Word, PDF…) read as one article. */
    fun fromBook(book: Book, now: Long = System.currentTimeMillis()): Book {
        val body = book.chapters.flatMap { c -> c.paragraphs.filterNot { it.heading }.map { it.text } }
        return build(book.title, body, now, PASTED, author = "本地文件")
    }

    fun firstUrl(text: String): String? = Regex("https?://[^\\s<>\"'“”]+").find(text)?.value?.trimEnd('.', ',', ')', '）', '。')

    /** Shared text that is nothing but a link (maybe with a page title around it). */
    fun isLinkOnly(text: String): Boolean {
        val url = firstUrl(text) ?: return false
        return TextLocator.wordCount(text.replace(url, " ")) <= 25
    }

    internal fun paragraphs(text: String): List<String> {
        val lines = text.lines()
        val hasBlankLines = lines.any { it.isBlank() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotBlank()) result += clean(current.toString()); current.clear() }
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            if (current.isEmpty()) { current.append(line); continue }
            val previous = current.trimEnd()
            val continues = hasBlankLines || (!terminal.containsMatchIn(previous) && line.first().isLowerCase())
            if (!continues) { flush(); current.append(line); continue }
            if (previous.endsWith('-') && line.first().isLowerCase()) { current.setLength(current.trimEnd().length - 1); current.append(line) }
            else current.append(' ').append(line)
        }
        flush()
        return result.filter { it.isNotBlank() }
    }

    private fun clean(text: String) = text.replace(Regex("[ \\t\\u00a0]+"), " ").trim()

    private fun autoTitle(paragraph: String): String {
        val sentence = TextLocator.sentenceAt(paragraph, 0).let { if (it.isEmpty()) paragraph else paragraph.substring(it) }
        val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size <= 10) sentence.trimEnd('.') else words.take(8).joinToString(" ").trimEnd(',', ';', ':') + "…"
    }

    internal fun build(title: String, body: List<String>, now: Long, format: String, author: String, collection: String = "",
        section: String = "", subtitle: String = "", source: String = ""): Book {
        require(body.isNotEmpty()) { "没有正文段落" }
        val paragraphs = listOf(Paragraph("@title", title, heading = true)) + body.mapIndexed { i, text -> Paragraph("p${i + 1}", text) }
        val chapter = Chapter("article", title, paragraphs, emptyList())
        val id = contentId("$format:$collection:$title\u0000" + body.joinToString("\u0000"))
        return Book(id, title, author, listOf(chapter), now, format, collection = collection, section = section, subtitle = subtitle, source = source)
    }

    /** Whole article text for AI context. Articles are short enough to send in full. */
    fun fullText(book: Book): String = buildString {
        append(book.title)
        if (book.subtitle.isNotBlank()) append("\n").append(book.subtitle)
        book.chapters.forEach { c -> c.paragraphs.filterNot { it.heading }.forEach { append("\n\n").append(it.text) } }
    }.take(MAX_AI_CONTEXT)

    /**
     * Sections whose topics postgraduate entrance exam (考研) reading passages are most often adapted from.
     * This is a topic heuristic, not a prediction.
     */
    private val examSections = setOf("leaders", "science & technology", "science and technology", "business", "finance & economics",
        "finance and economics", "culture", "international", "by invitation", "united states", "britain", "essay", "special report",
        "technology quarterly", "obituary", "graphic detail")
    fun examTopic(section: String) = section.trim().lowercase() in examSections
    fun examLength(words: Int) = words in 400..1200
}

/** Splits one issue of The Economist (EPUB from the weekly packaging) into separate articles. */
object EconomistIssue {
    private const val MIN_WORDS = 120

    fun looksLike(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 80.toByte() || bytes[1] != 75.toByte()) return false
        return runCatching { val files = unzip(bytes); isEconomist(files) }.getOrDefault(false)
    }

    /** Returns null when the EPUB is not an Economist issue. */
    fun read(bytes: ByteArray, now: Long = System.currentTimeMillis()): List<Book>? {
        val files = unzip(bytes)
        if (!isEconomist(files)) return null
        val opfPath = opfPath(files)
        val opf = Jsoup.parse(decode(files.getValue(opfPath)), "", Parser.xmlParser())
        val issue = issueName(opf.selectFirst("dc|title, title")?.text().orEmpty())
        val manifest = opf.select("manifest > item").associate { it.attr("id") to resolve(opfPath, it.attr("href")) }
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
        val ncxPath = opf.select("manifest > item").firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
            ?.let { resolve(opfPath, it.attr("href")) }
        val ncx = ncxPath?.let { files[it] }?.let { Jsoup.parse(decode(it), "", Parser.xmlParser()) }
            ?: error("这期经济学人缺少目录")
        val books = mutableListOf<Book>()
        val seen = mutableSetOf<String>()
        ncx.select("navMap > navPoint").forEach { sectionPoint ->
            val section = sectionPoint.selectFirst("> navLabel > text")?.text()?.trim().orEmpty()
            val points = sectionPoint.select("> navPoint").ifEmpty { org.jsoup.select.Elements(sectionPoint) }
            points.forEach { point ->
                val label = point.selectFirst("> navLabel > text")?.text()?.trim().orEmpty()
                val src = point.selectFirst("> content")?.attr("src").orEmpty().substringBefore('#')
                if (src.isBlank()) return@forEach
                val path = resolve(ncxPath, src)
                val base = base(path)
                if (!seen.add(base)) return@forEach
                val parts = spine.filter { base(it) == base }.ifEmpty { listOf(path) }
                article(parts.mapNotNull { files[it] }, label, section, issue, now + books.size)?.let { books += it }
            }
        }
        require(books.isNotEmpty()) { "没有在这期经济学人里找到文章正文" }
        return books
    }

    private fun article(documents: List<ByteArray>, label: String, section: String, issue: String, now: Long): Book? {
        var title = ""; var rubric = ""; var date = ""
        var started = false
        val body = mutableListOf<String>()
        documents.forEach { bytes ->
            val doc = Jsoup.parse(decode(bytes))
            doc.body().allElements.forEach { e ->
                when {
                    e.hasClass("te_article_title") -> { title = e.text().trim(); started = true }
                    e.hasClass("te_article_rubric") -> rubric = e.text().trim()
                    e.hasClass("te_article_datepublished") -> date = e.text().trim()
                    started && e.normalName() == "p" && e.parents().none { it.normalName() == "p" } -> {
                        val copy = e.clone()
                        copy.select("img").remove()
                        copy.select("span.te_section_title").forEach { if (it.text().trim() == "■") it.remove() }
                        val text = copy.text().replace(' ', ' ').replace(Regex("\\s+"), " ").trim().removeSuffix("■").trim()
                        if (text.isNotEmpty()) body += text
                    }
                }
            }
        }
        if (!started || body.sumOf { TextLocator.wordCount(it) } < MIN_WORDS) return null
        val heading = title.ifBlank { label }.ifBlank { return null }
        return Articles.build(heading, body, now, Articles.ECONOMIST, author = listOf("The Economist", date).filter { it.isNotBlank() }.joinToString(" · "),
            collection = issue, section = section, subtitle = rubric)
    }

    private fun isEconomist(files: Map<String, ByteArray>): Boolean {
        val opfPath = runCatching { opfPath(files) }.getOrNull() ?: return false
        val opf = decode(files[opfPath] ?: return false)
        if (!opf.contains("economist", ignoreCase = true)) return false
        return files.entries.any { (path, bytes) -> (path.endsWith(".html") || path.endsWith(".xhtml")) && decode(bytes).contains("te_article_title") }
    }

    private fun opfPath(files: Map<String, ByteArray>): String {
        val container = Jsoup.parse(decode(requireNotNull(files["META-INF/container.xml"]) { "缺少 EPUB container.xml" }), "", Parser.xmlParser())
        return container.selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() } ?: error("EPUB 未指定正文目录")
    }

    /** "TheEconomist.2026.11.21" -> "The Economist · 2026-11-21" */
    internal fun issueName(raw: String): String {
        val date = Regex("(\\d{4})[.\\-_ ](\\d{1,2})[.\\-_ ](\\d{1,2})").find(raw)?.destructured
        return if (date != null) "The Economist · ${date.component1()}-${date.component2().padStart(2, '0')}-${date.component3().padStart(2, '0')}"
        else raw.ifBlank { "The Economist" }
    }

    private fun base(path: String) = path.replace(Regex("_split_\\d+(?=\\.[A-Za-z]+$)"), "")

    private fun resolve(base: String, href: String): String {
        val directory = base.substringBeforeLast('/', "")
        val parts = (if (directory.isEmpty()) emptyList() else directory.split('/')).toMutableList()
        java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8").split('/').forEach { part ->
            when (part) { "", "." -> Unit; ".." -> { require(parts.isNotEmpty()) { "EPUB 包含无效内部路径" }; parts.removeAt(parts.lastIndex) }; else -> parts += part }
        }
        return parts.joinToString("/")
    }

    private fun decode(bytes: ByteArray) = DocumentImporter.decode(bytes)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            var count = 0
            var entry = zip.nextEntry
            while (entry != null) {
                require(++count <= 6000) { "EPUB 内部文件过多" }
                val name = entry.name
                require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "EPUB 包含无效内部路径" }
                // Images are not needed for reading; skip their bytes to keep memory low.
                val wanted = !entry.isDirectory && Regex("\\.(x?html?|opf|ncx|xml)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
                if (wanted) {
                    val data = zip.readLimited(16 * 1024 * 1024)
                    total += data.size
                    require(total <= 100L * 1024 * 1024) { "EPUB 解压后过大" }
                    files[name] = data
                }
                entry = zip.nextEntry
            }
        }
        return files
    }
}
