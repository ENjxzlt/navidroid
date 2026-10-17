package dev.navidroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test
    fun lineSyncedLrcSpreadsWordsAcrossLine() {
        val lrc = """
            [ar:Someone]
            [00:12.00]Streetlights hum a quiet tune
            [00:16.50]I count the cars
        """.trimIndent()
        val lyrics = LyricsParser.parseLrc(lrc, 200_000, "test")!!
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        val first = lyrics.lines[0]
        assertEquals(12_000, first.startMs)
        assertEquals(16_500, first.endMs)
        assertEquals(listOf("Streetlights", "hum", "a", "quiet", "tune"), first.words.map { it.text })
        assertEquals(12_000, first.words.first().startMs)
        // Wörter folgen lückenlos aufeinander und enden vor der nächsten Zeile.
        first.words.zipWithNext { a, b -> assertEquals(a.endMs, b.startMs) }
        assertTrue(first.words.last().endMs <= 16_500)
    }

    @Test
    fun enhancedLrcUsesWordTimestamps() {
        val lrc = "[00:01.00]<00:01.00>Hold <00:01.40>the <00:01.60>summer\n[00:03.00]next"
        val line = LyricsParser.parseLrc(lrc, 10_000, "test")!!.lines[0]
        assertEquals(listOf("Hold", "the", "summer"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L, 1_600L), line.words.map { it.startMs })
        assertEquals(3_000, line.words.last().endMs)
        assertEquals("Hold the summer", line.text)
    }

    @Test
    fun multipleTimestampsAndFractions() {
        val lrc = "[00:05.5][01:00.123]Chorus\n[00:10]Verse"
        val lines = LyricsParser.parseLrc(lrc, 120_000, "test")!!.lines
        assertEquals(listOf(5_500L, 10_000L, 60_123L), lines.map { it.startMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lines.map { it.text })
    }

    @Test
    fun lineAtFindsActiveLine() {
        val lyrics = LyricsParser.parseLrc("[00:01.00]a\n[00:02.00]b\n[00:03.00]c", 10_000, "test")!!
        assertEquals(-1, lyrics.lineAt(500))
        assertEquals(0, lyrics.lineAt(1_000))
        assertEquals(1, lyrics.lineAt(2_999))
        assertEquals(2, lyrics.lineAt(9_000))
    }

    @Test
    fun structuredLyricsApplyOffsetAndPlainFallback() {
        val synced = StructuredLyrics(synced = true, offset = 500, line = listOf(LyricsLine(2_000, "Hi there")))
        assertEquals(1_500, LyricsParser.fromStructured(synced, 5_000, "x").lines[0].startMs)
        val plain = StructuredLyrics(synced = false, line = listOf(LyricsLine(null, "one"), LyricsLine(null, "two")))
        val parsed = LyricsParser.fromStructured(plain, 5_000, "x")
        assertFalse(parsed.synced)
        assertEquals(listOf("one", "two"), parsed.lines.map { it.text })
    }

    @Test
    fun textWithoutTimestampsIsNotSynced() {
        assertNull(LyricsParser.parseLrc("just text\nno times", 1_000, "x"))
    }
}
