package com.lyricstage.core

import com.lyricstage.core.lyrics.LrcParser
import com.lyricstage.core.lyrics.LyricsBuilder
import com.lyricstage.core.lyrics.LyricsKind
import com.lyricstage.core.lyrics.TitleCleaner
import com.lyricstage.core.lyrics.TrackKey
import com.lyricstage.core.lyrics.WordTimingEstimator
import com.lyricstage.core.text.Graphemes
import com.lyricstage.core.text.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun parsesTimestamps() {
        assertEquals(83_450L, LrcParser.toMs("01", "23.45"))
        assertEquals(83_500L, LrcParser.toMs("01", "23.5"))
        assertEquals(83_005L, LrcParser.toMs("01", "23.005"))
        assertEquals(60_000L, LrcParser.toMs("1", "00"))
    }

    @Test fun multipleTimestampsPerRow() {
        val rows = LrcParser.parse("[00:10.00][00:30.00]Chorus line\n[00:20.00]Verse")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), rows.map { it.startMs })
        assertEquals("Chorus line", rows[2].text)
    }

    @Test fun enhancedWordTagsAndOffset() {
        val lyrics = LyricsBuilder.fromLrc(Samples.read("enhanced"), 10_000)
        assertEquals(LyricsKind.WORD_SYNCED, lyrics.kind)
        val first = lyrics.lines[0]
        assertEquals(listOf("Light", "the", "fire", "tonight"), first.words.map { it.text })
        // [offset:+100] shifts everything 100 ms earlier
        assertEquals(900L, first.startMs)
        assertEquals(1_300L, first.words[1].startMs)
        assertEquals(first.words[1].startMs, first.words[0].endMs)
    }

    @Test fun estimatedWordsAreOrderedAndInsideTheLine() {
        val lyrics = LyricsBuilder.fromLrc(Samples.read("english"), 60_000)
        assertEquals(LyricsKind.LINE_SYNCED, lyrics.kind)
        for (line in lyrics.lines) {
            var prevEnd = line.startMs
            for (w in line.words) {
                assertTrue("${w.text} starts before previous end", w.startMs >= prevEnd)
                assertTrue(w.endMs > w.startMs)
                prevEnd = w.endMs
            }
            assertTrue("pause before line end: ${line.text}", line.words.last().endMs < line.endMs)
        }
    }

    @Test fun syllableWeights() {
        assertEquals(1.0, WordTimingEstimator.weight("light", Script.LATIN), 0.0)
        assertEquals(3.0, WordTimingEstimator.weight("promises", Script.LATIN), 0.0)
        // Indic weight counts aksharas, not code points
        assertEquals(3.0, WordTimingEstimator.weight("रोशनी", Script.DEVANAGARI), 0.0)
        assertEquals(2.0, WordTimingEstimator.weight("ख़्वाबों", Script.DEVANAGARI), 0.0)
    }

    @Test fun graphemesKeepConjuncts() {
        assertEquals(listOf("ख़्वा", "बों"), Graphemes.split("ख़्वाबों"))
        assertEquals(listOf("स", "न्ध्या"), Graphemes.split("सन्ध्या"))
        assertEquals(listOf("h", "e", "y"), Graphemes.split("hey"))
    }

    @Test fun sameTimestampBecomesSubtitle() {
        val lyrics = LyricsBuilder.fromLrc(Samples.read("japanese"), 30_000)
        assertEquals("yozora ni ukabu hoshi wo kazoete", lyrics.lines[0].sub)
        assertEquals(Script.JAPANESE, lyrics.lines[0].script)
        assertTrue(lyrics.lines[0].words.size in 2..8)
    }

    @Test fun chorusRepeatsCounted() {
        val lyrics = LyricsBuilder.fromLrc(Samples.read("english"), 60_000)
        assertEquals(2, lyrics.lines.first().repeats)
    }

    @Test fun backingVocalsMarked() {
        val lyrics = LyricsBuilder.fromLrc(Samples.read("english"), 60_000)
        val line = lyrics.lines.first { it.text.startsWith("(carry") }
        assertEquals(listOf(true, true, true, false, false, false), line.words.map { it.backing })
    }

    @Test fun lineAtLookup() {
        val lyrics = LyricsBuilder.fromLrc(Samples.read("english"), 60_000)
        assertEquals(-1, lyrics.lineAt(5_000))
        assertEquals(0, lyrics.lineAt(10_000))
        assertEquals(1, lyrics.lineAt(15_000))
    }

    @Test fun cleansTitles() {
        assertEquals("Song", TitleCleaner.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", TitleCleaner.cleanTitle("Song - Remastered 2011"))
        assertEquals("Song", TitleCleaner.cleanTitle("Song (Official Video)"))
        assertEquals("Song", TitleCleaner.cleanTitle("Song [Official Lyric Video]"))
        assertEquals("Artist", TitleCleaner.cleanArtist("Artist, Other & Third"))
        assertEquals("Artist", TitleCleaner.cleanArtist("Artist - Topic"))
    }

    @Test fun trackKeysAreNormalised() {
        val a = TrackKey.of("Beyoncé", "Halo (Official Video)", 261_400)
        val b = TrackKey.of("beyonce", "Halo", 261_000)
        assertEquals(a, b)
        assertEquals("beyonce|halo|261", a)
        assertNotEquals(TrackKey.of("अरिजीत", "तुम ही हो", 1000), TrackKey.of("अरिजीत", "तम ह ह", 1000))
    }
}
