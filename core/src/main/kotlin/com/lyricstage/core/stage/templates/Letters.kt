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

/** Per-glyph reveal: each letter flips up into place, staggered. */
object Letters : LyricTemplate {
    override val id = TemplateId.LETTERS
    override val perGlyph = true
    private val spring = Spring(0.6f, 420f)
    private const val STAGGER_MS = 32L

    override fun rowOptions(line: MeasuredLine) = defaultRows(line.words.size, 5)

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement =
        arrangeRows(line, fontPx, rows, Align.CENTRE, gapEm = 1.1f, jitter = Rng(seed + 9))

    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
    }

    override fun glyphPose(p: WordPlacement, glyph: Int, sinceEnterMs: Long, out: Pose) {
        out.reset()
        // long words keep the total stagger under ~300 ms
        val t = sinceEnterMs - glyph * STAGGER_MS
        if (t <= 0) {
            out.alpha = 0f
            return
        }
        val v = spring.value(t)
        if (v >= 1f) return
        val u = 1f - v
        out.dy = -u * p.height * 0.4f * (if (p.jitter >= 0) 1 else -1)
        out.scaleY = v.coerceAtLeast(0.05f)
        out.alpha = (t / 80f).coerceIn(0f, 1f)
    }
}
