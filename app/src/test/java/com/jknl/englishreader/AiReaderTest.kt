package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiReaderTest {
    @Test fun chineseJsonPreservesParagraphsAndCountsCharacters() {
        val raw = """{"format":"englishreader.book.v1","language":"zh","title":"雨","chapters":[{"id":"c1","title":"第一章","paragraphs":[{"id":"p1","text":"雨停了。她回家了。"}]}]}"""
        val book = BookFiles.read(raw.byteInputStream())
        assertTrue(book.isChinese)
        assertEquals("7 字", book.countLabel)
        assertEquals("中文正文", book.chapters.single().coverage())
        assertEquals("雨停了。她回家了。", book.chapters.single().paragraphs.last().text)
        assertTrue(book.chapters.single().alignments.isEmpty())
    }
    @Test fun translationProvenanceSurvivesDirectAndCompanionImport() {
        val raw = """{"format":"englishreader.book.v1","title":"Rain","chapters":[{"id":"c1","title":"One","paragraphs":[{"id":"p1","sentences":[{"en":"Rain stopped.","zh":"雨停了。","translation_source":"published"},{"en":"She left.","zh":"她走了。","translation_source":"ai"}]}]}]}"""
        val book = BookFiles.read(raw.byteInputStream())
        val chapter = book.chapters.single()
        assertEquals(listOf("原译本", "AI 补译"), chapter.alignments.map { it.source })
        val original = book.copy(chapters = listOf(chapter.copy(alignments = emptyList())))
        val document = ChineseImport.read(raw.byteInputStream(), "bilingual.json")
        val updated = ChineseImport.prepare(original, CompanionMatching.plans(original, document), false).book
        assertEquals(listOf("原译本", "AI 补译"), updated.chapters.single().alignments.map { it.source })
        assertEquals(original.id, updated.id)
        assertThrows(IllegalArgumentException::class.java) { BookFiles.read(raw.replace("published", "guessed").byteInputStream()) }
    }
    private fun story() = Book("qa", "Story", "", listOf(Chapter("c", "One", listOf(
        Paragraph("before", "Already read."), Paragraph("focus", "A bird waited. Then it left."),
        Paragraph("future", "UNREAD SPOILER")), emptyList())), 0)
    @Test fun questionContainsOnlySelectedTextAndPrecedingContextAndMergesOverlaps() {
        val book = story()
        val q = ReadingQuestions.selection(book, listOf(PassageRange(0,1,0,6), PassageRange(0,1,2,14)))
        assertEquals("A bird waited.", q.text)
        assertEquals(1, q.passages.size)
        assertTrue(q.preceding.contains("Already read."))
        val body = ReadingQuestions.body("test-model", q, listOf(ReadingExchange("Earlier question", "Earlier answer")), "Why?")
        assertFalse(body.contains("UNREAD SPOILER"))
        assertFalse(body.contains("Then it left."))
        val messages = JSONObject(body).getJSONArray("messages")
        assertEquals("Why?", messages.getJSONObject(messages.length()-1).getString("content"))
        assertTrue(body.contains("Earlier answer"))
    }
    @Test fun emptyAndOversizeSelectionsAndQuestionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReadingQuestions.selection(story(), emptyList()) }
        val book = story().copy(chapters = listOf(Chapter("c", "One", listOf(Paragraph("p", "a".repeat(12001))), emptyList())))
        assertThrows(IllegalArgumentException::class.java) { ReadingQuestions.selection(book, listOf(PassageRange(0,0,0,12001))) }
        val selection = ReadingQuestions.selection(story(), listOf(PassageRange(0,1,0,14)))
        assertThrows(IllegalArgumentException::class.java) { ReadingQuestions.body("m", selection, emptyList(), " ") }
        assertThrows(IllegalArgumentException::class.java) { ReadingQuestions.body("m", selection, emptyList(), "a".repeat(2001)) }
    }
    @Test fun lookupIdentityUsesContextNotModelAndRetainsOccurrenceAndSource() {
        val first = WordQuery("bird", "A bird sees a bird.", 2)
        val other = first.copy(start = 14)
        val origin = WordOrigin("book", "Book", "chapter", "Chapter")
        val id = WordRecord.identity(first, origin)
        assertNotEquals(id, WordRecord.identity(other, origin))
        assertNotEquals(id, WordRecord.identity(first, origin.copy(bookId = "other")))
        assertEquals(id, WordRecord.create("cache-one", first, origin, "{}", "model-one", LearningGoal.KAOYAN).id)
        assertEquals(id, WordRecord.create("cache-two", first, origin, "{}", "model-two", LearningGoal.LITERATURE).id)
    }
}
