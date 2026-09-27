package com.jknl.englishreader.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Text-first EPUB importer. No archive paths are ever written to the filesystem. */
class EpubImporter {
    fun read(input: InputStream): Book {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                require(++count <= 6000) { "EPUB 内部文件过多" }
                val path = safePath(entry.name)
                require(!files.containsKey(path)) { "EPUB 包含重复路径" }
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var read = zip.read(buffer)
                while (read != -1) {
                    total += read
                    require(total <= 100L * 1024 * 1024) { "EPUB 解压后超过 100 MB，暂不支持" }
                    require(output.size() + read <= 16 * 1024 * 1024) { "EPUB 单个文件过大，暂不支持" }
                    output.write(buffer, 0, read)
                    read = zip.read(buffer)
                }
                if (!entry.isDirectory) files[path] = output.toByteArray()
                entry = zip.nextEntry
            }
        }
        require(files.isNotEmpty()) { "文件不是可读取的 EPUB" }
        val container = xml(requireNotNull(files["META-INF/container.xml"]) { "缺少 EPUB container.xml" })
        val rootfile = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element
        val opfPath = safePath(requireNotNull(rootfile) { "EPUB 未指定正文目录" }.getAttribute("full-path"))
        val opf = xml(requireNotNull(files[opfPath]) { "找不到 EPUB 内容目录" })
        val title = opf.getElementsByTagNameNS("*", "title").item(0)?.textContent?.trim().orEmpty().ifEmpty { "未命名书籍" }
        val author = opf.getElementsByTagNameNS("*", "creator").item(0)?.textContent?.trim().orEmpty().ifEmpty { "作者未注明" }
        val manifest = mutableMapOf<String, String>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val e = items.item(i) as Element
            if (e.getAttribute("media-type") in setOf("application/xhtml+xml", "text/html")) {
                manifest[e.getAttribute("id")] = resolve(opfPath, e.getAttribute("href"))
            }
        }
        val refs = opf.getElementsByTagNameNS("*", "itemref")
        val chapters = mutableListOf<Chapter>()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(title.toByteArray(Charsets.UTF_8))
        for (i in 0 until refs.length) {
            val e = refs.item(i) as Element
            if (e.getAttribute("linear") == "no") continue
            val path = requireNotNull(manifest[e.getAttribute("idref")]) { "EPUB 正文格式暂不支持" }
            val bytes = requireNotNull(files[path]) { "找不到章节文件：$path" }
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
            val chapter = chapter(path, bytes, chapters.size + 1)
            if (chapter.paragraphs.isNotEmpty()) chapters += chapter
        }
        require(chapters.isNotEmpty()) { "没有找到可阅读的正文（暂不支持扫描或加密书籍）" }
        require(chapters.map { it.id }.distinct().size == chapters.size) { "EPUB 章节路径重复" }
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
        return Book(id, title, author, chapters, System.currentTimeMillis())
    }

    private fun chapter(path: String, bytes: ByteArray, number: Int): Chapter {
        val doc = xml(bytes)
        val body = doc.getElementsByTagNameNS("*", "body").item(0) ?: doc.documentElement
        val paragraphs = mutableListOf<Paragraph>()
        val anchors = mutableMapOf<String, SourceRange>()
        val pending = mutableListOf<Pair<List<String>, String>>()
        val blocks = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre")

        fun addBlock(element: Element) {
            val builder = StringBuilder()
            val marks = mutableListOf<Mark>()
            val localAnchors = mutableListOf<Pair<String, IntRange>>()
            fun inline(node: Node) {
                if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                    // Collapse HTML whitespace while preserving space across inline element boundaries.
                    node.nodeValue.forEach { c ->
                        if (c.isWhitespace() || c == '\u00a0') {
                            if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                        } else builder.append(c)
                    }
                    return
                }
                val e = node as? Element ?: return
                val tag = (e.localName ?: e.tagName).lowercase()
                if (tag in setOf("script", "style", "rt", "rp")) return
                if (tag == "br") { if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' '); return }
                val start = builder.length
                for (i in 0 until e.childNodes.length) inline(e.childNodes.item(i))
                val end = builder.length
                if (end > start) {
                    if (tag in setOf("i", "em")) marks += Mark(start, end, "italic")
                    if (tag in setOf("b", "strong")) marks += Mark(start, end, "bold")
                    val anchor = e.getAttribute("data-er-id").ifEmpty { e.getAttribute("id") }
                    if (anchor.isNotEmpty()) localAnchors += anchor to (start until end)
                }
            }
            inline(element)
            val text = builder.toString().trimEnd()
            if (text.isBlank()) return
            val id = "p${paragraphs.size}"
            paragraphs += Paragraph(id, text, (element.localName ?: element.tagName).matches(Regex("h[1-6]")),
                marks.map { it.copy(end = it.end.coerceAtMost(text.length)) }.filter { it.start < it.end })
            localAnchors.forEach { (anchor, range) ->
                val end = (range.last + 1).coerceAtMost(text.length)
                require(!anchors.containsKey(anchor)) { "章节 $number 中的英文标记 $anchor 重复" }
                if (end > range.first) anchors[anchor] = SourceRange(id, range.first, end)
            }
        }

        fun visit(node: Node) {
            val e = node as? Element ?: return
            val tag = (e.localName ?: e.tagName).lowercase()
            if (tag in setOf("script", "style", "head", "nav")) return
            val targets = e.getAttribute("data-er-for")
            if (targets.isNotBlank()) {
                val chinese = e.textContent.trim()
                require(chinese.isNotBlank()) { "章节 $number 有空中文译文" }
                pending += targets.trim().split(Regex("\\s+")) to chinese
                return
            }
            if (tag in blocks) {
                val nested = (0 until e.childNodes.length).any { (e.childNodes.item(it) as? Element)?.let { child -> (child.localName ?: child.tagName).lowercase() in blocks } == true }
                if (!nested) { addBlock(e); return }
            }
            for (i in 0 until e.childNodes.length) visit(e.childNodes.item(i))
        }
        visit(body)
        val alignments = pending.mapIndexed { i, (targets, chinese) ->
            Alignment("epub-$i", targets.map { requireNotNull(anchors[it]) { "章节 $number 的中文找不到英文标记：$it" } }, chinese, "双语 EPUB")
        }
        return Chapter(path, paragraphs.firstOrNull { it.heading }?.text ?: "第 $number 节", paragraphs, alignments).also(Alignments::validate)
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        // Android's XML implementation does not expose every Apache-specific feature.
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        // EPUB 2 often declares an external XHTML DTD. Never resolve it; retain numeric/XML entities.
        var source = bytes.toString(Charsets.UTF_8)
        require(!source.contains("<!ENTITY", ignoreCase = true)) { "暂不支持包含自定义 XML 实体的 EPUB" }
        source = source.replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "").replace("&nbsp;", "&#160;")
        require(!source.contains("<!DOCTYPE", ignoreCase = true)) { "EPUB 的 XML 声明无效" }
        return builder.parse(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)))
    }

    private fun resolve(base: String, href: String): String {
        val uri = URI(null, null, base, null).resolve(href.replace(" ", "%20")).normalize()
        require(!uri.isAbsolute && uri.authority == null) { "EPUB 正文不能来自远程地址" }
        return safePath(uri.path)
    }

    private fun safePath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && path.split('/').none { it == ".." }) { "EPUB 包含无效内部路径" }
        return path
    }
}
