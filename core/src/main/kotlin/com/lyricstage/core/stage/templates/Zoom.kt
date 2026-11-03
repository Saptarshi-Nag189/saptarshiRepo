package com.lyricstage.core.stage.templates

import com.lyricstage.core.stage.Align
import com.lyricstage.core.stage.Arrangement
import com.lyricstage.core.stage.LyricTemplate
import com.lyricstage.core.stage.MeasuredLine
import com.lyricstage.core.stage.Pose
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.Spring
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.core.stage.WordPlacement
import com.lyricstage.core.stage.arrangeRows
import com.lyricstage.core.stage.defaultRows
import kotlin.math.abs

/** Each word punches in from scale 2 to 1, blurred while it moves fast. */
object Zoom : LyricTemplate {
    override val id = TemplateId.ZOOM
    private val spring = Spring(0.6f, 300f)

    override fun rowOptions(line: MeasuredLine) = defaultRows(line.words.size, 5)

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement =
        arrangeRows(line, fontPx, rows, Align.CENTRE)

    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
        val v = spring.value(sinceEnterMs)
        if (v >= 1f) return
        val u = 1f - v
        out.scaleX = 1f + u
        out.scaleY = 1f + u
        out.alpha = (sinceEnterMs / 110f).coerceIn(0f, 1f)
        out.blurPx = abs(u) * 16f
    }
}
