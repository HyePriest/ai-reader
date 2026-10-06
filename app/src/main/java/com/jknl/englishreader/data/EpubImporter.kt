package com.jknl.englishreader.data

import org.w3c.dom.Element
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
    fun read(input: InputStream, onCover: (String, ByteArray) -> Unit = { _, _ -> }): Book {
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
        val navigation = mutableSetOf<String>()
        val tocPaths = mutableListOf<String>()
        val coverPaths = mutableListOf<String>()
        val coverId = (0 until opf.getElementsByTagNameNS("*", "meta").length).map { opf.getElementsByTagNameNS("*", "meta").item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val e = items.item(i) as Element
            if (e.getAttribute("properties").split(Regex("\\s+")).contains("cover-image") || e.getAttribute("id") == coverId)
                coverPaths += resolve(opfPath, e.getAttribute("href"))
            if (e.getAttribute("media-type") in setOf("application/xhtml+xml", "text/html")) {
                manifest[e.getAttribute("id")] = resolve(opfPath, e.getAttribute("href"))
                if (e.getAttribute("properties").split(Regex("\\s+")).contains("nav")) navigation += e.getAttribute("id")
            }
            if (e.getAttribute("media-type") == "application/x-dtbncx+xml") tocPaths += resolve(opfPath, e.getAttribute("href"))
        }
        val chapterTitles = mutableMapOf<String, String>()
        data class TocLink(val label: String, val target: HtmlReferences.Target, val depth: Int)
        val tocLinks = mutableListOf<TocLink>()
        navigation.mapNotNull { manifest[it] }.forEach { navPath ->
            files[navPath]?.let { bytes ->
                val nav = org.jsoup.Jsoup.parse(DocumentImporter.decode(bytes))
                val toc = nav.select("nav[epub:type~=toc], nav[role=doc-toc]").ifEmpty { nav.select("nav").filter { it.attr("epub:type") !in setOf("landmarks", "page-list") }.let { org.jsoup.select.Elements(it) } }
                toc.select("li").forEach { li ->
                    val label = li.children().firstOrNull { it.normalName() in setOf("a", "span") } ?: return@forEach
                    val a = if (label.normalName() == "a") label else li.selectFirst("a[href]") ?: return@forEach
                    HtmlReferences.resolve(navPath, a.attr("href"))?.let { target ->
                        if (label.text().isNotBlank()) {
                            chapterTitles.putIfAbsent(target.path, label.text())
                            tocLinks += TocLink(label.text(), target, (li.parents().count { it.normalName() == "ol" } - 1).coerceAtLeast(0))
                        }
                    }
                }
                if (tocLinks.isEmpty()) toc.select("a[href]").forEach { a ->
                    HtmlReferences.resolve(navPath, a.attr("href"))?.let { target ->
                        if (a.text().isNotBlank()) { chapterTitles.putIfAbsent(target.path, a.text()); tocLinks += TocLink(a.text(), target, 0) }
                    }
                }
            }
        }
        if (tocLinks.isEmpty()) tocPaths.forEach { tocPath -> files[tocPath]?.let { bytes ->
            val ncx = xml(bytes)
            val points = ncx.getElementsByTagNameNS("*", "navPoint")
            for (i in 0 until points.length) {
                val point = points.item(i) as Element
                val href = (point.getElementsByTagNameNS("*", "content").item(0) as? Element)?.getAttribute("src").orEmpty()
                val label = point.getElementsByTagNameNS("*", "navLabel").item(0)?.textContent?.trim().orEmpty()
                if (href.isNotBlank() && label.isNotBlank()) HtmlReferences.resolve(tocPath, href)?.let { target ->
                    chapterTitles.putIfAbsent(target.path, label)
                    var depth = 0; var parent = point.parentNode
                    while (parent != null) { if (parent.localName == "navPoint") depth++; parent = parent.parentNode }
                    tocLinks += TocLink(label, target, depth)
                }
            }
        } }
        val refs = opf.getElementsByTagNameNS("*", "itemref")
        val chapters = mutableListOf<Chapter>()
        val documents = manifest.values.distinct().mapNotNull { path -> files[path]?.let { bytes ->
            val raw = DocumentImporter.decode(bytes)
            require(!raw.contains("<!ENTITY", ignoreCase = true)) { "暂不支持包含自定义 XML 实体的 EPUB" }
            path to org.jsoup.Jsoup.parse(raw)
        } }.toMap()
        val references = HtmlReferences(documents)
        val anchorsByPath = mutableMapOf<String, Map<String, SourceRange>>()
        val guideLabels = mutableMapOf<String, String>()
        val guide = opf.getElementsByTagNameNS("*", "reference")
        for (i in 0 until guide.length) {
            val e = guide.item(i) as Element
            val target = HtmlReferences.resolve(opfPath, e.getAttribute("href")) ?: continue
            guideLabels[target.path] = e.getAttribute("title").ifBlank { when (e.getAttribute("type")) {
                "toc" -> "目录"; "cover" -> "封面"; "preface" -> "前言"; "copyright-page" -> "版权页"; "notes" -> "注释"; else -> "正文"
            } }
            if (e.getAttribute("type") == "cover") documents[target.path]?.selectFirst("img[src], image")?.let { image ->
                val href = image.attr("src").ifBlank { image.attr("xlink:href").ifBlank { image.attr("href") } }
                HtmlReferences.resolve(target.path, href)?.let { coverPaths += it.path }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(title.toByteArray(Charsets.UTF_8))
        val readingPaths = (0 until refs.length).mapNotNull { i ->
            val e = refs.item(i) as Element
            manifest[e.getAttribute("idref")]
        }.toMutableList()
        readingPaths += tocLinks.map { it.target.path }.filter { it in documents && it !in readingPaths }
        documents.forEach { (path, doc) -> doc.select("a[href]").forEach { a ->
            if (references.note(path, a) != null) HtmlReferences.resolve(path, a.attr("href"))?.let { target ->
                if (target.path in documents && target.path !in readingPaths) readingPaths += target.path
                if (target.path != path) guideLabels.putIfAbsent(target.path, "注释")
            }
        } }
        for (path in readingPaths.distinct()) {
            val bytes = requireNotNull(files[path]) { "找不到章节文件：$path" }
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
            // Keep the original source fingerprint stable even when a navigation page is omitted.
            val body = requireNotNull(documents[path]).body().clone()
            if (path in navigation.mapNotNull { manifest[it] }) body.select("nav").remove()
            val parsed = HtmlChapterReader.read(body, path, guideLabels[path] ?: chapterTitles[path] ?: "正文",
                noteText = { references.note(path, it) }, onAnchors = { anchorsByPath[path] = it })
            val chapter = parsed.copy(title = chapterTitles[path] ?: guideLabels[path] ?: parsed.title)
            if (chapter.paragraphs.isNotEmpty()) chapters += chapter
        }
        require(chapters.isNotEmpty()) { "没有找到可阅读的正文（暂不支持扫描或加密书籍）" }
        require(chapters.map { it.id }.distinct().size == chapters.size) { "EPUB 章节路径重复" }
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
        val contents = tocLinks.mapNotNull { link ->
            val ci = chapters.indexOfFirst { it.id == link.target.path }
            if (ci < 0) return@mapNotNull null
            val anchor = anchorsByPath[link.target.path]?.get(link.target.fragment)
            // A missing fragment cannot silently send the reader to the wrong subsection.
            if (link.target.fragment.isNotEmpty() && anchor == null) return@mapNotNull null
            val pi = anchor?.let { a -> chapters[ci].paragraphs.indexOfFirst { it.id == a.paragraph } } ?: 0
            ContentsEntry(link.label, ci, pi.coerceAtLeast(0), anchor?.start ?: 0, link.depth)
        }.distinct()
        val completeContents = contents.toMutableList()
        chapters.forEachIndexed { ci, c ->
            if (contents.none { it.chapter == ci }) {
                val at = completeContents.indexOfFirst { it.chapter > ci }.let { if (it < 0) completeContents.size else it }
                completeContents.add(at, ContentsEntry(c.title, ci))
            }
        }
        coverPaths.distinct().firstNotNullOfOrNull { files[it] }?.let { onCover(id, it) }
        return Book(id, title, author, chapters, System.currentTimeMillis(), "epub", completeContents)
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
        var source = DocumentImporter.decode(bytes)
        require(!source.contains("<!ENTITY", ignoreCase = true)) { "暂不支持包含自定义 XML 实体的 EPUB" }
        source = source.replace(Regex("<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE), "").replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "").replace("&nbsp;", "&#160;")
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
