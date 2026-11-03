package com.lyricstage.core.stage.templates

import com.lyricstage.core.stage.Align
import com.lyricstage.core.stage.Arrangement
import com.lyricstage.core.stage.LyricTemplate
import com.lyricstage.core.stage.MeasuredLine
import com.lyricstage.core.stage.Pose
import com.lyricstage.core.stage.Rng
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.Spring
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.core.stage.WordPlacement
import com.lyricstage.core.stage.arrangeRows
import com.lyricstage.core.stage.defaultRows

/** Words stacked in rows; emphasised words are bigger, filler words smaller. Each word springs up. */
object Stack : LyricTemplate {
    override val id = TemplateId.STACK
    private val spring = Spring.Bouncy

    override fun rowOptions(line: MeasuredLine) = defaultRows(line.words.size, 6)

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement {
        val r = Rng(seed)
        val align = when ((r.next() * 3).toInt()) { 0 -> Align.START; 1 -> Align.END; else -> Align.CENTRE }
        val scales = FloatArray(line.words.size) { i ->
            val w = line.line.words[i]
            when {
                w.emphasis -> 1.3f
                Filler.isFiller(w.text) -> 0.72f
                else -> 1f
            }
        }
        return arrangeRows(line, fontPx, rows, align, scales, leading = 1.0f)
    }

    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
        val v = spring.value(sinceEnterMs)
        if (v >= 1f) return
        val u = 1f - v
        out.dy = u * p.height * 0.7f
        out.scaleY = 1f + u * 0.3f
        out.scaleX = 1f - u * 0.1f
        out.alpha = (sinceEnterMs / 120f).coerceIn(0f, 1f)
    }
}
