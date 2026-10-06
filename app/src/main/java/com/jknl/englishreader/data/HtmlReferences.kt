package com.jknl.englishreader.data

import org.jsoup.nodes.Element
import java.net.URI

/** Local hyperlinks only. Chapter links are never mistaken for footnotes. */
internal class HtmlReferences(private val documents: Map<String, Element>) {
    data class Target(val path: String, val fragment: String)
    companion object {
        fun resolve(base: String, href: String): Target? = runCatching {
            val uri = URI(null, null, base, null).resolve(href.trim().replace(" ", "%20")).normalize()
            require(!uri.isAbsolute && uri.authority == null)
            val path = uri.path.orEmpty()
            require(!path.startsWith('/') && !path.contains('\\') && path.split('/').none { it == ".." })
            Target(path, uri.fragment.orEmpty())
        }.getOrNull()

        private fun tokens(e: Element) = (e.attr("epub:type") + " " + e.attr("role") + " " + e.className()).lowercase().split(Regex("\\s+"))
        private fun isNote(e: Element) = tokens(e).any { it in setOf("footnote", "endnote", "doc-footnote", "doc-endnote", "note", "footnotes", "endnotes", "doc-endnotes") }
    }
    private val anchors = documents.mapValues { (_, body) ->
        val index = linkedMapOf<String, Element>()
        body.getAllElements().forEach { e ->
            if (e.id().isNotBlank()) index.putIfAbsent(e.id(), e)
            if (e.attr("name").isNotBlank()) index.putIfAbsent(e.attr("name"), e)
        }
        index
    }
    fun note(base: String, link: Element): String? {
        val href = link.attr("href").ifBlank { link.attr("l:href").ifBlank { link.attr("xlink:href") } }
        val target = resolve(base, href) ?: return null
        if (target.fragment.isBlank()) return null
        val e = anchors[target.path]?.get(target.fragment) ?: return null
        val flags = tokens(link)
        if (flags.any { it in setOf("backlink", "doc-backlink") } || link.text().trim() in setOf("↩", "↵", "↑", "返回", "back")) return null
        val explicit = flags.any { it in setOf("noteref", "doc-noteref", "footnote", "footnote-ref") } || link.attr("type") == "note"
        val markedTarget = (sequenceOf(e) + e.parents().asSequence()).any(::isNote)
        val numbered = link.text().trim().matches(Regex("[\\[（(【〔]?[0-9０-９①-⑳一二三四五六七八九十*†‡]+[\\]）)】〕.、]?"))
        if (!explicit && !markedTarget && !(numbered && (link.parent()?.normalName() == "sup" || link.selectFirst("sup") != null || target.fragment.contains(Regex("(?i)(note|foot|fn|endnote)"))))) return null
        var content = e
        // An empty <a name=...> or an inline number belongs to the surrounding note paragraph.
        if (content.normalName() in setOf("a", "span", "sup")) {
            content = content.parents().firstOrNull { it.normalName() in setOf("p", "li", "aside", "dd") }
                ?: content.nextElementSibling()?.takeIf { content.text().isBlank() }
                ?: content.parents().firstOrNull { it.normalName() == "div" } ?: content
        }
        val clone = content.clone()
        clone.select("a[epub:type=backlink], a[role=doc-backlink], script, style").remove()
        clone.select("a[href]").filter { it.text().trim() in setOf("↩", "↵", "↑", "返回", "back") }.forEach { it.remove() }
        clone.select("br").forEach { it.before("\n") }
        clone.select("p, li, div, h1, h2, h3").forEach { it.appendText("\n") }
        return clone.wholeText().trim().takeIf { it.isNotBlank() }
    }
}
