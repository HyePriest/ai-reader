package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ExamTest {
    private val paper = """
        2024 年全国硕士研究生招生考试英语（一）

        Section I Use of English

        Directions:
        Read the following text. Choose the best word(s) for each numbered blank and mark A, B,
        C or D on the ANSWER SHEET. (10 points)

        There’s nothing more welcoming than a door opening for you. __1__ the need to be
        touched to open or close, doors are essential in __2__ access.
        Self-sliding doors began to emerge.

        1. A. Through B. Despite C. Besides D. Without

        2. A. revealing B. demanding C. improving D. tracing

        Section II Reading Comprehension

        Part A
        Directions:
        Read the following four texts.

        Text 1
        Nearly 2,000 years ago, as the Romans began to pull out of Scotland, they left behind a
        curious treasure: 10 tons of nails, nearly a million of the things. The nail hoard was
        discovered in 1960 in a four-metre-deep pit.
        Why had the Romans buried a million nails? The likely explanation is that the
        withdrawal was rushed.

        21. The Romans buried the nails probably for the sake of ____

        A. saving them for future use.

        B. keeping them from rusting.

        C. letting them grow in value.

        D. hiding them from the
        locals.

        22. What is the text mainly about?
        A. Nails.
        B. Romans.
        C. Iron.
        D. Scotland.

        Section III Writing
        Part A
        51. Directions:
        Write an email to Paul in about 100 words.
    """.trimIndent()

    @Test fun splitsPaperIntoClozeReadingAndWriting() {
        val parts = ExamPaper.read(paper, now = 0)
        assertEquals(listOf("完形填空", "阅读 Text 1", "写作 Part A"), parts.map { it.title })
        assertTrue(parts.all { it.collection == "2024 考研英语（一）" && it.isArticle })
        val cloze = parts[0]
        assertEquals(listOf(1, 2), cloze.questions.map { it.number })
        assertEquals(listOf("Through", "Despite", "Besides", "Without"), cloze.questions[0].options)
        val clozeBody = cloze.chapters.single().paragraphs.filterNot { it.heading }.map { it.text }
        assertEquals(2, clozeBody.size)
        assertTrue(clozeBody[0].contains("(1)____ the need to be touched"))
        val reading = parts[1]
        assertEquals(listOf(21, 22), reading.questions.map { it.number })
        assertEquals("hiding them from the locals.", reading.questions[0].options[3])
        assertEquals("The Romans buried the nails probably for the sake of ____", reading.questions[0].stem)
        val body = reading.chapters.single().paragraphs.filterNot { it.heading }.map { it.text }
        assertEquals(2, body.size)
        assertTrue(body[0].endsWith("in a four-metre-deep pit."))
        assertTrue(Articles.fullText(reading).contains("D. hiding them from the locals."))
    }

    @Test fun answerKeysInCommonFormats() {
        assertEquals(mapOf(21 to 'D', 22 to 'D', 23 to 'A', 24 to 'B', 25 to 'A'), AnswerKey.parse("21-25 DDABA"))
        assertEquals(mapOf(1 to 'B', 2 to 'C', 41 to 'E'), AnswerKey.parse("1.B 2、C\n41：E"))
        assertEquals(mapOf(1 to 'B', 2 to 'C', 3 to 'A'), AnswerKey.parse("１－３：ＢＣＡ"))
        assertEquals("21-25 DDABA", AnswerKey.format(AnswerKey.parse("21-25 DDABA")))
    }

    @Test fun realPapersWhenAvailable() {
        val dir = System.getenv("EXAM_DIR")
        assumeTrue(dir != null && File(dir).isDirectory)
        File(dir!!).listFiles()!!.filter { it.extension == "md" }.sorted().forEach { f ->
            val raw = f.readText()
            assertTrue(ExamPaper.looksLike(raw))
            val parts = ExamPaper.read(raw)
            println("== ${f.name}: ${parts.first().collection}")
            parts.forEach { p ->
                val body = p.chapters.single().paragraphs.filterNot { it.heading }
                println("${p.section} | ${p.title} | ${p.questions.map { it.number }} | ${body.size} paras | ${body.firstOrNull()?.text?.take(60)}")
                p.questions.forEach { q -> assertEquals("${p.title} ${q.number}", 4, q.options.size) }
            }
            val numbers = parts.flatMap { it.questions }.map { it.number }
            assertEquals((1..40).toList(), numbers)
        }
    }
}

class TitleTranslationTest {
    @Test fun requestUsesJsonModeWithLowercaseJsonInPrompt() {
        val body = org.json.JSONObject(TitleTranslation.body("deepseek-flash", listOf("Can Britannia rule the waves again?")))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("json"))
    }
    @Test fun parsesPlainAndFencedReplies() {
        fun reply(content: String) = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
            .put("message", org.json.JSONObject().put("content", content)))).toString()
        assertEquals(mapOf(0 to "英国能否再次称霸海洋？", 1 to "测试"), TitleTranslation.parse(reply("{\"1\":\"英国能否再次称霸海洋？\",\"2\":\"测试\"}"), 2))
        assertEquals(mapOf(0 to "甲"), TitleTranslation.parse(reply("```json\n{\"1\":\"甲\"}\n```"), 1))
        assertThrows(IllegalArgumentException::class.java) { TitleTranslation.parse(reply("抱歉"), 1) }
    }
}
