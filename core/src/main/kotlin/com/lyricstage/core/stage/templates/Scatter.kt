package com.lyricstage.core.stage.templates

import com.lyricstage.core.stage.Arrangement
import com.lyricstage.core.stage.LyricTemplate
import com.lyricstage.core.stage.MeasuredLine
import com.lyricstage.core.stage.Pose
import com.lyricstage.core.stage.REF_PX
import com.lyricstage.core.stage.Rng
import com.lyricstage.core.stage.Rows
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.Spring
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.core.stage.WordPlacement
import com.lyricstage.core.stage.defaultRows

/**
 * A collage: words of different sizes land at semi-random spots. Each row is its own
 * horizontal band and words in a band never share x, so nothing overlaps by construction.
 */
object Scatter : LyricTemplate {
    override val id = TemplateId.SCATTER
    private val spring = Spring(0.6f, 240f)

    override fun rowOptions(line: MeasuredLine) = defaultRows(line.words.size, 5)

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement {
        val r = Rng(seed + 13)
        val k = fontPx / REF_PX
        val n = line.words.size
        val scales = FloatArray(n) { i -> if (line.line.words[i].emphasis) 1.4f else r.range(0.7f, 1.3f) }
        val widths = List(n) { line.words[it].width * k * scales[it] }
        val gaps = FloatArray(n) { line.spaceWidth * k * r.range(0.6f, 1.8f) }
        val parts = Rows.balance(widths, rows, line.spaceWidth * k * 1.2f)

        val rowWidths = parts.map { range -> range.sumOf { (widths[it] + gaps[it]).toDouble() }.toFloat() - gaps[range.last] }
        val maxW = rowWidths.maxOrNull() ?: 0f
        val out = ArrayList<WordPlacement>(n)
        var top = 0f
        for ((ri, range) in parts.withIndex()) {
            val heights = range.map { line.words[it].height * k * scales[it] }
            val band = heights.max() * 1.3f
            // shift each row left or right inside the widest row's span
            var x = (maxW - rowWidths[ri]) * r.next()
            for ((j, i) in range.withIndex()) {
                val h = heights[j]
                out += WordPlacement(
                    index = i,
                    x = x,
                    y = top + (band - h) * r.next(),
                    fontPx = fontPx * scales[i],
                    width = widths[i],
                    height = h,
                    ascent = line.words[i].ascent * k * scales[i],
                    row = ri,
                    jitter = r.signed(),
                    side = if (r.chance(0.5f)) 1 else -1,
                )
                x += widths[i] + gaps[i]
            }
            top += band
        }
        return Arrangement(out)
    }

    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
        val v = spring.value(sinceEnterMs)
        if (v >= 1f) return
        val u = 1f - v
        out.dx = u * stage.width * 0.35f * p.side
        out.dy = u * stage.height * 0.3f * p.jitter
        out.rotationDeg = u * 18f * p.jitter
        out.scaleX = 1f - u * 0.3f
        out.scaleY = 1f - u * 0.3f
        out.alpha = (sinceEnterMs / 100f).coerceIn(0f, 1f)
        out.blurPx = kotlin.math.abs(u) * 12f
    }
}
