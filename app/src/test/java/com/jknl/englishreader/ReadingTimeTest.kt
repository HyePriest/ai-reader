package com.jknl.englishreader

import com.jknl.englishreader.data.*
import org.junit.Assert.*
import org.junit.Test

class ReadingTimeTest {
    @Test fun allocationFreeWordCountRetainsOriginalTokenRules() {
        val original = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")
        val samples = listOf("Don't stop—it's a test.", "mother-in-law isn’t here", "123 中文 café A--B", "", "'a' b- c’ d")
        samples.forEach { assertEquals(original.findAll(it).count(), TextLocator.wordCount(it)) }
    }
    @Test fun countsForegroundOnlyAndNeverCountsCheckpointsTwice() {
        var now = 0L
        val timer = ReadingTimer { now }
        now = 1000
        assertEquals(0L, timer.checkpoint())
        assertEquals(0L, timer.setRunning(true))
        now = 6000
        assertEquals(5000L, timer.checkpoint())
        assertEquals(0L, timer.checkpoint())
        now = 8000
        assertEquals(2000L, timer.setRunning(false))
        now = 80000
        assertEquals(0L, timer.checkpoint())
        assertEquals(0L, timer.setRunning(true))
        now = 85000
        assertEquals(5000L, timer.setRunning(false))
        assertEquals(0L, timer.setRunning(false))
    }
    @Test fun repeatedResumeDoesNotLoseAnInterval() {
        var now = 100L
        val timer = ReadingTimer { now }
        timer.setRunning(true)
        now = 2100
        assertEquals(2000L, timer.setRunning(true))
        now = 2600
        assertEquals(500L, timer.setRunning(false))
    }
    @Test fun progressUsesTextLengthsAcrossChaptersAndClampsStalePositions() {
        val book = ShelfBook("id", "title", "", 0, listOf(ShelfChapter(2, listOf(10, 20), false), ShelfChapter(3, listOf(30, 40), true)))
        assertEquals(.15f, book.progress(ReadingPosition(0, 1, 5)), .001f)
        assertEquals(.8f, book.progress(ReadingPosition(1, 1, 20)), .001f)
        assertEquals(1f, book.progress(ReadingPosition(99, 99, 99)), .001f)
        assertEquals(0f, book.progress(ReadingPosition(-1, -1, -1)), .001f)
    }
    @Test fun durationsDoNotRoundUnreadBooksUpToOneMinute() {
        assertEquals("0 秒", readingDuration(0))
        assertEquals("59 秒", readingDuration(59999))
        assertEquals("1 分钟", readingDuration(60000))
        assertEquals("2 小时 3 分钟", readingDuration(7380000))
    }
}
