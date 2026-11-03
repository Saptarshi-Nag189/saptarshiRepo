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
import kotlin.math.abs

/** Staggered rows; words slide in from alternating sides with a skew and motion blur. */
object Slide : LyricTemplate {
    override val id = TemplateId.SLIDE
    private val spring = Spring(0.65f, 260f)

    override fun rowOptions(line: MeasuredLine): List<Int> {
        val n = line.words.size
        val preferred = ((n + 1) / 2).coerceIn(1, 3)
        return (preferred..n.coerceAtMost(5)).toList() + (1 until preferred).reversed()
    }

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement {
        val r = Rng(seed + 5)
        val step = r.range(0.25f, 0.6f)
        val reverse = r.chance(0.5f)
        return arrangeRows(
            line, fontPx, rows, Align.START, leading = 0.98f,
            indent = { row, count -> (if (reverse) count - 1 - row else row) * step },
            side = { row, _ -> if ((row % 2 == 0) != reverse) -1 else 1 },
        )
    }

    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
        val v = spring.value(sinceEnterMs)
        if (v >= 1f) return
        val u = 1f - v
        // words from the left travel less, so they never sweep across the word before them
        val travel = if (p.side < 0) p.width * 1.1f else stage.width * 0.45f
        out.dx = u * travel * p.side
        out.skewX = u * 0.25f * -p.side
        out.scaleX = 1f + abs(u) * 0.25f
        out.alpha = (sinceEnterMs / 90f).coerceIn(0f, 1f)
        out.blurPx = abs(u) * 18f
    }
}
