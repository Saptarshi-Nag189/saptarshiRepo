package com.lyricstage.core

import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.stage.REF_PX
import com.lyricstage.core.stage.TextMeasure
import com.lyricstage.core.stage.WordMetrics
import com.lyricstage.core.text.Graphemes

object Samples {
    val names = listOf("english", "hindi", "bengali", "japanese", "enhanced", "rap")

    fun read(name: String): String =
        requireNotNull(Samples::class.java.getResource("/samples/$name.lrc")) { "missing $name" }.readText()

    /**
     * Pessimistic width estimate for heavy type (wider than Inter Black on average), so a
     * layout that fits here fits on the device too.
     */
    val measure = TextMeasure { text, _ ->
        val glyphs = Graphemes.split(text)
        val offsets = FloatArray(glyphs.size)
        var x = 0f
        var indic = false
        for ((i, g) in glyphs.withIndex()) {
            offsets[i] = x
            val c = g.codePointAt(0)
            x += REF_PX * when {
                c in 0x3040..0x9FFF -> 1.0f
                c in 0x0900..0x09FF -> { indic = true; 0.78f }
                g == " " -> 0.28f
                g in setOf("M", "W", "m", "w") -> 0.98f
                g in setOf("i", "l", "I", "j", ".", ",", "'", "!", "|") -> 0.34f
                g[0].isUpperCase() -> 0.76f
                else -> 0.64f
            }
        }
        WordMetrics(
            width = x,
            ascent = REF_PX * if (indic) 1.15f else 0.97f,
            descent = REF_PX * if (indic) 0.45f else 0.27f,
            glyphs = glyphs,
            glyphOffsets = offsets,
        )
    }

    fun line(text: String) = LyricLine(0, text, 0, 1000, emptyList(), com.lyricstage.core.text.Script.detect(text))
}
