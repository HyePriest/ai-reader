package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WordLookupTest {
    private val answer = """{"lemma":"bank","pos":"名词 n.","context_meaning":"河岸","context_explanation":"水边的地形。","senses":[{"pos":"名词","meaning":"银行；河岸","usage":"常用"}],"word_advice":{"decision":"需要背","reason":"两个核心义常见。"},"sense_advice":{"decision":"需要背","reason":"叙事常用。"},"phrase":"","uncertainty":""}"""
    @Test fun sendsCompleteContextAndExactSelectedOccurrence() {
        val sentence = "The bank stood by the river bank."
        val q = WordQuery("bank", sentence, sentence.lastIndexOf("bank"))
        val request = JSONObject(WordPrompt.request(q, "deepseek-flash"))
        val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(sentence, data.getString("sentence"))
        assertEquals(28, data.getInt("word_start_utf16"))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.getBoolean("stream"))
        assertThrows(IllegalArgumentException::class.java) { WordQuery("wrong", sentence, 0) }
    }
    @Test fun validatesCompleteAnswersAndRejectsTruncation() {
        val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", answer)))).toString()
        val parsed = WordExplanation.parse(WordPrompt.response(body))
        assertEquals("河岸", parsed.contextMeaning)
        assertEquals("需要背", parsed.senseAdvice.decision)
        assertThrows(IllegalArgumentException::class.java) { WordPrompt.response(body.replace("stop", "length")) }
        assertThrows(IllegalArgumentException::class.java) { WordExplanation.parse(answer.replace("需要背", "排名4000")) }
        assertThrows(Exception::class.java) { WordExplanation.parse("{}") }
    }
    @Test fun repeatedParagraphIdsInDifferentChaptersDoNotToggleEachOther() {
        val p = Paragraph("p1", "A bank.")
        val first = Selection(p, SourceRange("p1", 0, 7), "bank", null, chapterIndex = 0, wordStart = 2)
        val second = first.copy(chapterIndex = 1)
        assertEquals(second, toggleSelection(first, second))
        assertEquals("A bank.", WordQuery.from(first).sentence)
        assertEquals(2, WordQuery.from(first).start)
    }
    @Test fun countsBodyOccurrencesWithoutHeadingsOrChinese() {
        val chapter = Chapter("c", "Chapter One", listOf(Paragraph("h", "Chapter One", heading = true),
            Paragraph("p", "Don't repeat well-known words. Words, words! 123 中文。")), emptyList())
        assertEquals(6, chapter.wordCount)
        assertEquals(12, Book("id", "Title", "Author", listOf(chapter, chapter), 0).wordCount)
    }
    @Test fun tapsOnSpacesAndPunctuationKeepTheCorrectReadingUnit() {
        val p = Paragraph("p", "Rain fell. Nora waited.", units = listOf(ReadingUnit(0, 10), ReadingUnit(11, 23)))
        assertEquals(0 until 10, TextLocator.readingUnitAt(p, 4))
        assertEquals(0 until 10, TextLocator.readingUnitAt(p, 9))
        assertEquals(11 until 23, TextLocator.readingUnitAt(p, 22))
        assertEquals("Nora", p.text.substring(TextLocator.nearestWord(p.text, 15, 11, 23)!!))
    }
    @Test fun learningGoalIsExplicitAndOldAdviceIsNotAcceptedAsANewResponse() {
        val q = WordQuery("bank", "A bank.", 2)
        val request = JSONObject(WordPrompt.request(q, "model", LearningGoal.KAOYAN))
        assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("当前目标：考研"))
        assertFalse(WordPrompt.system(LearningGoal.GENERAL) == WordPrompt.system(LearningGoal.KAOYAN))
        val old = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", answer.replace("需要背", "见到能懂"))))).toString()
        assertThrows(IllegalArgumentException::class.java) { WordPrompt.response(old) }
        assertEquals("见到能懂", WordExplanation.parse(answer.replace("需要背", "见到能懂")).wordAdvice.decision)
    }
    @Test fun errorsGiveUsefulActionsWithoutExposingServerBody() {
        assertTrue(WordPrompt.httpError(401).contains("API Key"))
        assertTrue(WordPrompt.httpError(402).contains("余额"))
        assertTrue(WordPrompt.httpError(429).contains("稍后"))
        assertTrue(WordPrompt.httpError(503).contains("服务"))
    }
    private fun response(content: String, finish: String = "stop") = JSONObject().put("choices", org.json.JSONArray()
        .put(JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString()

    @Test fun acceptsFencedJsonAndRepairsTruncatedOutputOnce() = runBlocking {
        assertEquals("bank", WordExplanation.parse(WordPrompt.response(response("```json\n$answer\n```"))).lemma)
        var calls = 0
        val raw = WordPrompt.complete(WordQuery("bank", "A bank.", 2), "model", LearningGoal.KAOYAN) { request ->
            calls++
            if (calls == 1) response("{", "length") else {
                assertEquals(6144, JSONObject(request).getInt("max_tokens"))
                response(answer)
            }
        }
        assertEquals(2, calls)
        assertEquals("河岸", WordExplanation.parse(raw).contextMeaning)
    }

    @Test fun repairIsBoundedAndDoesNotRetryTransportErrors() = runBlocking {
        val query = WordQuery("bank", "A bank.", 2)
        var calls = 0
        try {
            WordPrompt.complete(query, "model", LearningGoal.KAOYAN) { calls++; response("{}") }
            fail("Malformed output must fail after one retry")
        } catch (_: WordResponseFormatException) { assertEquals(2, calls) }
        calls = 0
        val failure = IllegalArgumentException("API Key 无效")
        try {
            WordPrompt.complete(query, "model", LearningGoal.KAOYAN) { calls++; throw failure }
            fail("Transport errors must propagate")
        } catch (e: IllegalArgumentException) { assertSame(failure, e); assertEquals(1, calls) }
    }
}
