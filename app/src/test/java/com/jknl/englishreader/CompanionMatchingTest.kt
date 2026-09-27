package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionMatchingTest {
    private val json = """{"format":"englishreader.book.v1","title":"Rain","chapters":[{"id":"ch1","title":"Chapter One","paragraphs":[{"id":"p1","sentences":[{"en":"The rain stopped.","zh":"雨停了。"},{"en":"She went home.","zh":"她回家了。"}]}]}]}"""
    private fun target() = Book("0123456789abcdef01234567", "Original EPUB", "A", listOf(
        Chapter("OPS/ch1.xhtml", "Chapter One", listOf(Paragraph("epub-p7", "The rain  stopped.\nShe went home.")), emptyList())), 0)
    private fun doc(raw: String = json) = ChineseImport.read(raw.byteInputStream(), "translated.json")

    @Test fun bilingualJsonSupplementsEpubWithoutPhoneIdsAndWithoutLengthMatching() {
        val original = target()
        val plans = CompanionMatching.plans(original, doc())
        assertEquals(1, plans.size)
        assertEquals(2, plans.single().pairs.size)
        val saved = ChineseImport.prepare(original, plans, false).book
        assertEquals(original.id, saved.id)
        assertEquals(original.chapters[0].paragraphs[0].text, saved.chapters[0].paragraphs[0].text)
        assertEquals("雨停了。", saved.chapters[0].translationAt("epub-p7", 1)?.chinese)
        assertEquals("她回家了。", saved.chapters[0].translationAt("epub-p7", 24)?.chinese)
    }

    @Test fun changedEnglishOrPunctuationCannotPretendToMatch() {
        assertThrows(IllegalArgumentException::class.java) { CompanionMatching.plans(target(), doc(json.replace("stopped.", "stopped!"))) }
        assertThrows(IllegalArgumentException::class.java) { CompanionMatching.plans(target(), doc(json.replace("rain", "snow"))) }
    }

    @Test fun ambiguousRepeatedParagraphIsRejectedInsteadOfPickingFirst() {
        val b = target()
        val c = b.chapters.single()
        val repeated = b.copy(chapters = listOf(c.copy(paragraphs = c.paragraphs + c.paragraphs.single().copy(id = "duplicate"))))
        assertThrows(IllegalArgumentException::class.java) { CompanionMatching.plans(repeated, doc()) }
    }

    @Test fun incompleteChineseStillUsesFullEnglishContextAndDoesNotCreateMissingTranslations() {
        val root = JSONObject(json)
        root.getJSONArray("chapters").getJSONObject(0).getJSONArray("paragraphs").getJSONObject(0)
            .getJSONArray("sentences").getJSONObject(1).put("zh", JSONObject.NULL)
        val result = ChineseImport.prepare(target(), CompanionMatching.plans(target(), doc(root.toString())), false).book
        assertEquals(1, result.chapters.single().alignments.size)
        assertNull(result.chapters.single().translationAt("epub-p7", 24))
    }

    @Test fun fullContextDisambiguatesRepeatedSentencesAndPreservesEmojiOffsets() {
        val raw = """{"format":"englishreader.book.v1","title":"Test","chapters":[{"title":"C","paragraphs":[{"sentences":[{"en":"Yes. Yes. A bird 🦜 flew.","zh":"是的。是的。一只鸟飞过。"}]}]}]}"""
        val source = BookFiles.readJson(JSONObject(raw))
        val target = source.copy(id = "abcdefabcdefabcdefabcdef", chapters = source.chapters.map { it.copy(id = "different-id", paragraphs = it.paragraphs.map { p -> p.copy(id = "original-${p.id}") }, alignments = emptyList()) })
        val saved = ChineseImport.prepare(target, CompanionMatching.plans(target, doc(raw)), false).book
        assertEquals("是的。是的。一只鸟飞过。", saved.chapters.single().alignments.single().chinese)
    }

    @Test fun chaptersAreResolvedByContentEvenWhenOrderDiffers() {
        val original = target()
        val unrelated = Chapter("preface", "Preface", listOf(Paragraph("p", "A different paragraph.")), emptyList())
        val b = original.copy(chapters = listOf(unrelated) + original.chapters)
        val plan = CompanionMatching.plans(b, doc()).single()
        assertEquals(1, plan.chapterIndex)
        val result = ChineseImport.prepare(b, listOf(plan), false).book
        assertEquals(unrelated, result.chapters.first())
    }
}
