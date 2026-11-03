package com.lyricstage.stage

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.stage.REF_PX
import com.lyricstage.core.stage.TextMeasure
import com.lyricstage.core.stage.WordMetrics
import com.lyricstage.core.text.Graphemes
import com.lyricstage.core.text.Script

/** Lays out one word on a single line at [fontPx]. */
fun TextMeasurer.word(text: String, script: Script, fontPx: Float, density: Density): TextLayoutResult = measure(
    text = text,
    style = StageFonts.word(script, fontPx, density),
    overflow = TextOverflow.Visible,
    softWrap = false,
    maxLines = 1,
    density = density,
)

/** Real advance width of a single-line layout (size.width is rounded up). */
val TextLayoutResult.advance: Float get() = if (lineCount > 0) getLineRight(0) - getLineLeft(0) else 0f

/** Backs the core's [TextMeasure] with Compose text layout at [REF_PX]. */
class ComposeTextMeasure(private val measurer: TextMeasurer, private val density: Density) : TextMeasure {
    override fun measure(text: String, line: LyricLine): WordMetrics {
        val r = measurer.word(text, line.script, REF_PX, density)
        val glyphs = Graphemes.split(text)
        val offsets = FloatArray(glyphs.size)
        var index = 0
        for (i in glyphs.indices) {
            offsets[i] = r.getHorizontalPosition(index.coerceAtMost(text.length), usePrimaryDirection = true)
            index += glyphs[i].length
        }
        return WordMetrics(
            width = r.advance,
            ascent = r.firstBaseline,
            descent = r.size.height - r.firstBaseline,
            glyphs = glyphs,
            glyphOffsets = offsets,
        )
    }
}
