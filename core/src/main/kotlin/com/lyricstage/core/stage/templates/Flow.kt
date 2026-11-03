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
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The whole line rides a smooth S-shaped bezier across the screen; upcoming words wait
 * dimmed and light up as they are sung. Good for long or fast lines.
 */
object Flow : LyricTemplate {
    override val id = TemplateId.FLOW
    override val showsUpcoming = true
    private val spring = Spring(0.8f, 220f)
    private const val SAMPLES = 64

    override fun rowOptions(line: MeasuredLine): List<Int> {
        val n = line.words.size
        val preferred = ((n + 4) / 5).coerceIn(1, 4)
        return (preferred..n.coerceAtMost(6)).toList() + (1 until preferred).reversed()
    }

    override fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement {
        val r = Rng(seed + 33)
        val k = fontPx / REF_PX
        val n = line.words.size
        val widths = List(n) { line.words[it].width * k }
        val gap = line.spaceWidth * k
        val parts = Rows.balance(widths, rows, gap)
        val h = line.lineHeight * k
        val flip = r.chance(0.5f)

        val out = ArrayList<WordPlacement>(n)
        var top = 0f
        for ((ri, range) in parts.withIndex()) {
            val length = range.sumOf { widths[it].toDouble() }.toFloat() + gap * (range.count() - 1)
            val amp = (h * 0.55f).coerceAtMost(length * 0.12f)
            val sign = if ((ri % 2 == 0) != flip) 1f else -1f
            val curve = Bezier(0f, 0f, length / 3f, -amp * sign, length * 2f / 3f, amp * sign, length, 0f)
            val table = curve.arcTable(SAMPLES)
            val curveLen = table.last()
            // centre the words along the curve (it is a little longer than the straight row)
            var s = (curveLen - length) / 2f
            val bandTop = top + amp + h / 2f
            for (i in range) {
                val mid = s + widths[i] / 2f
                val t = curve.tAtLength(table, mid)
                val px = curve.x(t)
                val py = curve.y(t)
                val angle = Math.toDegrees(atan2(curve.dy(t), curve.dx(t)).toDouble()).toFloat()
                out += WordPlacement(
                    index = i,
                    x = px - widths[i] / 2f,
                    y = bandTop + py - h / 2f,
                    fontPx = fontPx,
                    width = widths[i],
                    height = h,
                    ascent = line.ascent * k,
                    rotationDeg = angle,
                    row = ri,
                )
                s += widths[i] + gap
            }
            top += h + amp * 2f + h * 0.2f
        }
        return Arrangement(out)
    }

    /** For Flow, [sinceEnterMs] counts from the line's entrance, not the word's. */
    override fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose) {
        out.reset()
        val v = spring.value(sinceEnterMs - p.row * 60L)
        if (v >= 1f) return
        val u = 1f - v
        out.dy = u * p.height * 0.5f
        out.alpha = v.coerceIn(0f, 1f)
    }

    /** Cubic bezier with arc-length lookup. */
    private class Bezier(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val x2: Float, val y2: Float, val x3: Float, val y3: Float,
    ) {
        private fun b(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
            val u = 1 - t
            return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
        }
        private fun db(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
            val u = 1 - t
            return 3 * u * u * (b - a) + 6 * u * t * (c - b) + 3 * t * t * (d - c)
        }
        fun x(t: Float) = b(t, x0, x1, x2, x3)
        fun y(t: Float) = b(t, y0, y1, y2, y3)
        fun dx(t: Float) = db(t, x0, x1, x2, x3)
        fun dy(t: Float) = db(t, y0, y1, y2, y3)

        /** Cumulative length at SAMPLES + 1 evenly spaced t values. */
        fun arcTable(samples: Int): FloatArray {
            val out = FloatArray(samples + 1)
            var px = x(0f)
            var py = y(0f)
            for (i in 1..samples) {
                val t = i / samples.toFloat()
                val nx = x(t)
                val ny = y(t)
                out[i] = out[i - 1] + hypot(nx - px, ny - py)
                px = nx
                py = ny
            }
            return out
        }

        fun tAtLength(table: FloatArray, len: Float): Float {
            val samples = table.size - 1
            if (len <= 0f) return 0f
            if (len >= table[samples]) return 1f
            var i = 1
            while (i < samples && table[i] < len) i++
            val seg = table[i] - table[i - 1]
            val f = if (seg > 0f) (len - table[i - 1]) / seg else 0f
            return (i - 1 + f) / samples
        }
    }
}
