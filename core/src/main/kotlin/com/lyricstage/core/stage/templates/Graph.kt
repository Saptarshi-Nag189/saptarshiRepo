package com.lyricstage.core.stage.templates

import com.lyricstage.core.stage.Arrangement
import com.lyricstage.core.stage.LyricTemplate
import com.lyricstage.core.stage.MeasuredLine
import com.lyricstage.core.stage.Pose
import com.lyricstage.core.stage.REF_PX
import com.lyricstage.core.stage.Rng
import com.lyricstage.core.stage.Rows
import com.lyricstage.core.stage.Segment
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.Spring
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.core.stage.WordPlacement
import com.lyricstage.core.stage.defaultRows

/**
 * Words plotted like a pitch line: each row rises or falls with a little random wobble,
 * joined by thin connectors that draw in as the next word is sung.
 */
object Graph : LyricTemplate {
    override val id = TemplateId.GRAPH
    private val spring = Spring(0.62f, 320f)

    override fun rowOptions(line: MeasuredLine) = defaultRows(line.words.size, 4)

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement {
        val r = Rng(seed + 21)
        val rising = r.chance(0.6f)
        val k = fontPx / REF_PX
        val n = line.words.size
        val scales = FloatArray(n) { i -> if (line.line.words[i].emphasis) 1.2f else if (Filler.isFiller(line.line.words[i].text)) 0.8f else 1f }
        val widths = List(n) { line.words[it].width * k * scales[it] }
        val gap = line.spaceWidth * k * 2.2f
        val parts = Rows.balance(widths, rows, gap)

        val words = ArrayList<WordPlacement>(n)
        val segments = ArrayList<Segment>()
        var top = 0f
        for ((ri, range) in parts.withIndex()) {
            val hMax = range.maxOf { line.words[it].height * k * scales[it] }
            val step = hMax * 0.32f
            // pitch: trend plus a wobble, as y offsets from the row's own top
            val offsets = ArrayList<Float>()
            var level = 0f
            for (j in 0 until range.count()) {
                offsets += level
                level += step * (if (rising) -1f else 1f) + step * 0.6f * r.signed()
            }
            val minOff = offsets.min()
            var x = 0f
            val rowStart = words.size
            for ((j, i) in range.withIndex()) {
                val h = line.words[i].height * k * scales[i]
                words += WordPlacement(
                    index = i,
                    x = x,
                    y = top + offsets[j] - minOff + (hMax - h),
                    fontPx = fontPx * scales[i],
                    width = widths[i],
                    height = h,
                    ascent = line.words[i].ascent * k * scales[i],
                    row = ri,
                    jitter = r.signed(),
                )
                x += widths[i] + gap
            }
            for (j in rowStart + 1 until words.size) {
                val a = words[j - 1]
                val b = words[j]
                val pad = gap * 0.18f
                segments += Segment(a.x + a.width + pad, a.y + a.height * 0.62f, b.x - pad, b.y + b.height * 0.62f, b.index)
            }
            val span = offsets.max() - minOff
            top += span + hMax * 1.25f
        }
        return Arrangement(words, segments)
    }

    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
        val v = spring.value(sinceEnterMs)
        if (v >= 1f) return
        val u = 1f - v
        out.dy = u * p.height * 0.8f
        out.scaleX = 1f - u * 0.15f
        out.scaleY = 1f - u * 0.15f
        out.alpha = (sinceEnterMs / 120f).coerceIn(0f, 1f)
    }
}
