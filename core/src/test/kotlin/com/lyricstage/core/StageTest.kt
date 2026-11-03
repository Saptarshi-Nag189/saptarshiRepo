package com.lyricstage.core

import com.lyricstage.core.lyrics.LyricsBuilder
import com.lyricstage.core.palette.Colour
import com.lyricstage.core.palette.PaletteBuilder
import com.lyricstage.core.palette.Swatch
import com.lyricstage.core.stage.Highlights
import com.lyricstage.core.stage.MeasuredLine
import com.lyricstage.core.stage.Rows
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.Spring
import com.lyricstage.core.stage.TemplateChooser
import com.lyricstage.core.stage.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTest {
    @Test fun rowsBalanceMinimisesWidestRow() {
        val rows = Rows.balance(listOf(5f, 1f, 1f, 1f, 1f, 5f), 2, 0f)
        assertEquals(listOf(0..2, 3..5), rows)
        assertEquals(1, Rows.balance(listOf(3f), 4, 1f).size)
    }

    @Test fun springSettlesExactly() {
        val s = Spring.Bouncy
        assertEquals(0f, s.value(0), 0f)
        assertTrue("overshoots", (1..400).any { s.value(it.toLong()) > 1f })
        assertEquals(1f, s.value(s.settleMs), 0f)
        assertTrue(s.settleMs in 300..1500)
    }

    @Test fun chooserNeverRepeatsAndIsStable() {
        for (name in Samples.names) {
            val lines = LyricsBuilder.fromLrc(Samples.read(name), 120_000).lines
            for (seed in 0 until 50) {
                val picks = TemplateChooser.chooseAll(lines, seed, null, false)
                for (i in 1 until picks.size) assertTrue("$name seed $seed line $i", picks[i] != picks[i - 1])
                assertEquals(picks, TemplateChooser.chooseAll(lines, seed, null, false))
                val calm = TemplateChooser.chooseAll(lines, seed, null, true)
                assertTrue(calm.none { it.name == "SCATTER" })
            }
        }
    }

    @Test fun highlightsOneToThree() {
        val lines = LyricsBuilder.fromLrc(Samples.read("english"), 120_000).lines
        for (seed in 0 until 30) for (l in lines) {
            val h = Highlights.pick(l, seed)
            assertTrue(h.size in 1..3)
            assertEquals(h, Highlights.pick(l, seed))
        }
    }

    @Test fun paletteTextContrast() {
        val p = PaletteBuilder.build(listOf(Swatch(0xFFE0C040.toInt(), 500), Swatch(0xFF3060E0.toInt(), 300), Swatch(0xFFF0F0F0.toInt(), 900)), 1)
        assertTrue(Colour.contrast(p.base, p.text) >= PaletteBuilder.MIN_CONTRAST)
        assertEquals(3, p.accents.size)
        assertTrue(Colour.darkenForContrast(0xFFFFFF00.toInt(), -1, 7.0).let { Colour.contrast(it, -1) >= 7.0 })
    }

    @Test fun templatesKeepWordsApart() {
        // settled words of row templates never overlap each other
        val line = MeasuredLine.measure(LyricsBuilder.fromLrc(Samples.read("english"), 60_000).lines[0], Samples.measure)
        for (t in Templates.all) {
            val layout = t.layout(line, SizeF(1080f, 2200f), 7)
            val boxes = layout.words.map { it.bounds() }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]
                val b = boxes[j]
                // allow a sliver for rotated Flow words touching at the corners
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertTrue("${t.id} words $i/$j overlap", overlapW <= a.width * 0.12f || overlapH <= a.height * 0.2f)
            }
        }
    }
}
