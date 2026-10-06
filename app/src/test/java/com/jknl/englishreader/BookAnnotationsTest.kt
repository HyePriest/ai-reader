package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookAnnotationsTest {
    private fun book(notes: String = "", paragraphNotes: String = "", zh: String = "雨停了。") = BookFiles.read("""
        {"format":"englishreader.book.v1","title":"Rain","chapters":[{"id":"c1","title":"One","paragraphs":[
        {"id":"p1","sentences":[{"en":"Rain stopped.","zh":"$zh"},{"en":"Rain, rain.","zh":null $notes}] $paragraphNotes},
        {"id":"p2","sentences":[{"en":"A quiet day.","zh":null}]}]}]}
    """.trimIndent().byteInputStream())

    @Test fun annotationsKeepSentenceOffsetsAndDoNotChangeBookIdentityOrTranslations() {
        val plain = book()
        val annotated = book(",\"annotations\":[{\"quote\":\"rain\",\"text\":\"小写\",\"source\":\"AI 注释\"},{\"text\":\"整句\"}]",
            ",\"annotations\":[{\"text\":\"整段\",\"title\":\"背景\"}]")
        val p = annotated.chapters.single().paragraphs[1]
        assertEquals(plain.id, annotated.id)
        assertEquals(plain.chapters.single().alignments, annotated.chapters.single().alignments)
        assertEquals(listOf("rain", "Rain, rain.", p.text), p.annotations.map { p.text.substring(it.start, it.end) })
        assertEquals("AI 注释", p.annotations.first().source)
        assertEquals("背景", p.annotations.last().title)
    }

    @Test fun repeatedQuotesRequireUnambiguousValidOccurrence() {
        fun read(fields: String) = BookAnnotations.read(JSONObject("""{"annotations":[{$fields}]}"""), "rain rain", "p")
        assertThrows(IllegalArgumentException::class.java) { read(""""quote":"rain","text":"说明"""") }
        assertEquals(5, read(""""quote":"rain","occurrence":2,"text":"说明"""").single().start)
        for (fields in listOf(
            """"quote":"missing","text":"说明"""",
            """"quote":"rain","occurrence":3,"text":"说明"""",
            """"quote":"rain","occurrence":1.5,"text":"说明"""",
            """"occurrence":1,"text":"说明"""",
            """"text":" """")) {
            assertThrows(IllegalArgumentException::class.java) { read(fields) }
        }
    }

    @Test fun chineseAndSurrogatePairsUseExactOriginalText() {
        val raw = """{"format":"englishreader.book.v1","language":"zh","title":"雨","chapters":[{"title":"一","paragraphs":[{"text":"🌧雨停了。","annotations":[{"quote":"雨","text":"天气"},{"text":"整段"}]}]}]}"""
        val p = BookFiles.read(raw.byteInputStream()).chapters.single().paragraphs.last()
        assertEquals(2, p.annotations.first().start)
        assertEquals("雨", p.text.substring(p.annotations.first().start, p.annotations.first().end))
        assertEquals(p.text.length, p.annotations.last().end)
    }

    @Test fun annotationSupplementPreservesOriginalTranslationAndUnspecifiedParagraphs() {
        val plain = book()
        val chapter = plain.chapters.single()
        val existing = plain.copy(chapters = listOf(chapter.copy(paragraphs = chapter.paragraphs.map {
            it.copy(annotations = listOf(BookAnnotation(0, it.text.length, "原注")))
        })))
        val incoming = book(",\"annotations\":[{\"text\":\"新注\"}]", zh = "改变的译文不应导入")
        val updated = BookAnnotations.supplement(existing, incoming)
        assertEquals(existing.id, updated.id)
        assertEquals(existing.importedAt, updated.importedAt)
        assertEquals(existing.chapters.single().alignments, updated.chapters.single().alignments)
        assertEquals("新注", updated.chapters.single().paragraphs[1].annotations.single().text)
        assertEquals(existing.chapters.single().paragraphs[2], updated.chapters.single().paragraphs[2])
        assertEquals(existing, BookAnnotations.supplement(existing, plain))
    }

    @Test fun allGuideExamplesImportAndAiOnlyPromptMarksEveryTranslation() {
        val guide = File("../docs/ai-book-preparation.md").readText()
        val examples = Regex("```json\\s*([\\s\\S]*?)```").findAll(guide).map { it.groupValues[1] }.toList()
        assertEquals(3, examples.size)
        examples.forEach {
            assertTrue(BookFiles.read(it.byteInputStream()).chapters.isNotEmpty())
            fun check(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        if (key == "annotations") {
                            val notes = value.getJSONArray(key)
                            for (i in 0 until notes.length()) {
                                val note = notes.getJSONObject(i)
                                assertTrue(note.getString("quote").isNotBlank())
                                assertFalse(note.has("title"))
                                assertFalse(note.has("source"))
                            }
                        } else check(value.get(key))
                    }
                    is org.json.JSONArray -> for (i in 0 until value.length()) check(value.get(i))
                }
            }
            check(JSONObject(it))
        }
        val ai = BookFiles.read(examples[1].byteInputStream())
        assertTrue(ai.chapters.flatMap { it.alignments }.all { it.source == "AI 补译" })
        assertTrue(ai.chapters.flatMap { it.paragraphs }.all { it.annotations.isEmpty() })
    }
}
