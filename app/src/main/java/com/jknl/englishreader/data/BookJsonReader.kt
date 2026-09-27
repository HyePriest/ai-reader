package com.jknl.englishreader.data

import android.util.JsonReader
import java.io.Reader

/** Decode straight into the book model, without retaining a second whole-book JSON tree/string. */
internal object BookJsonReader {
    fun read(source: Reader): Book = JsonReader(source).use { r ->
        var schema = 0
        var id: String? = null; var title: String? = null; var author: String? = null
        var importedAt: Long? = null; var chapters: List<Chapter>? = null
        r.fields { name -> when (name) {
            "schema" -> schema = r.nextInt()
            "id" -> id = r.nextString()
            "title" -> title = r.nextString()
            "author" -> author = r.nextString()
            "importedAt" -> importedAt = r.nextLong()
            "chapters" -> chapters = r.items { chapter(r) }
            else -> r.skipValue()
        } }
        require(schema == 1) { "这本书的数据版本暂不支持，请保留原文件" }
        Book(requireNotNull(id), requireNotNull(title), requireNotNull(author),
            requireNotNull(chapters).also { require(it.isNotEmpty()) }, requireNotNull(importedAt))
    }

    private fun chapter(r: JsonReader): Chapter {
        var id: String? = null; var title: String? = null
        var paragraphs: List<Paragraph>? = null; var alignments: List<Alignment>? = null
        r.fields { name -> when (name) {
            "id" -> id = r.nextString()
            "title" -> title = r.nextString()
            "paragraphs" -> paragraphs = r.items { paragraph(r) }
            "alignments" -> alignments = r.items { alignment(r) }
            else -> r.skipValue()
        } }
        return Chapter(requireNotNull(id), requireNotNull(title), requireNotNull(paragraphs), requireNotNull(alignments))
            .also(Alignments::validate)
    }

    private fun paragraph(r: JsonReader): Paragraph {
        var id: String? = null; var text: String? = null; var heading = false
        var marks = emptyList<Mark>(); var units = emptyList<ReadingUnit>(); var notes = emptyList<WordNote>()
        r.fields { name -> when (name) {
            "id" -> id = r.nextString()
            "text" -> text = r.nextString()
            "heading" -> heading = r.nextBoolean()
            "marks" -> marks = r.items {
                var start = -1; var end = -1; var kind: String? = null
                r.fields { when (it) {
                    "start" -> start = r.nextInt(); "end" -> end = r.nextInt(); "kind" -> kind = r.nextString()
                    else -> r.skipValue()
                } }
                Mark(start, end, requireNotNull(kind))
            }
            "units" -> units = r.items {
                var start = -1; var end = -1
                r.fields { when (it) {
                    "start" -> start = r.nextInt(); "end" -> end = r.nextInt(); else -> r.skipValue()
                } }
                ReadingUnit(start, end)
            }
            "notes" -> notes = r.items {
                var start = -1; var end = -1; var term: String? = null; var meaning: String? = null
                r.fields { when (it) {
                    "start" -> start = r.nextInt(); "end" -> end = r.nextInt()
                    "term" -> term = r.nextString(); "meaning" -> meaning = r.nextString(); else -> r.skipValue()
                } }
                WordNote(start, end, requireNotNull(term), requireNotNull(meaning))
            }
            else -> r.skipValue()
        } }
        val body = requireNotNull(text)
        require(marks.all { it.start >= 0 && it.end >= it.start && it.end <= body.length }) { "正文样式范围无效" }
        return Paragraph(requireNotNull(id), body, heading, marks, units, notes)
    }

    private fun alignment(r: JsonReader): Alignment {
        var id: String? = null; var chinese: String? = null; var source: String? = null
        var ranges: List<SourceRange>? = null
        r.fields { name -> when (name) {
            "id" -> id = r.nextString(); "chinese" -> chinese = r.nextString(); "source" -> source = r.nextString()
            "ranges" -> ranges = r.items {
                var paragraph: String? = null; var start = -1; var end = -1
                r.fields { when (it) {
                    "paragraph" -> paragraph = r.nextString(); "start" -> start = r.nextInt(); "end" -> end = r.nextInt()
                    else -> r.skipValue()
                } }
                SourceRange(requireNotNull(paragraph), start, end)
            }
            else -> r.skipValue()
        } }
        return Alignment(requireNotNull(id), requireNotNull(ranges), requireNotNull(chinese), requireNotNull(source))
    }

    private inline fun JsonReader.fields(read: (String) -> Unit) {
        beginObject()
        while (hasNext()) read(nextName())
        endObject()
    }

    private inline fun <T> JsonReader.items(read: () -> T): List<T> {
        val items = ArrayList<T>()
        beginArray()
        while (hasNext()) items.add(read())
        endArray()
        return items
    }
}
