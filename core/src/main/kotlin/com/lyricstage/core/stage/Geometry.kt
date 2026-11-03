package com.lyricstage.core.stage

import com.lyricstage.core.lyrics.LyricLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class SizeF(val width: Float, val height: Float) {
    val minDimension: Float get() = min(width, height)
}

data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2
    val centreY: Float get() = (top + bottom) / 2

    fun contains(o: RectF, slack: Float = 0.5f): Boolean =
        o.left >= left - slack && o.top >= top - slack && o.right <= right + slack && o.bottom <= bottom + slack

    fun overlaps(o: RectF): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    companion object {
        val Empty = RectF(0f, 0f, 0f, 0f)
    }
}

/** Font size every word is measured at; widths scale linearly from here. */
const val REF_PX = 100f

/** Metrics of one word at [REF_PX]. */
class WordMetrics(
    val width: Float,
    /** Distance from the box top to the baseline. */
    val ascent: Float,
    /** Distance from the baseline to the box bottom. */
    val descent: Float,
    /** Grapheme clusters of the word, for per-letter templates. */
    val glyphs: List<String> = emptyList(),
    /** Left edge of each glyph, relative to the word, at [REF_PX]. */
    val glyphOffsets: FloatArray = FloatArray(0),
) {
    val height: Float get() = ascent + descent
}

/** Measures words at [REF_PX]. The app backs this with Compose's TextMeasurer. */
fun interface TextMeasure {
    fun measure(text: String, line: LyricLine): WordMetrics
}

/** A line plus its measured words, computed once per lyrics load. */
class MeasuredLine(
    val line: LyricLine,
    val words: List<WordMetrics>,
    /** Width of a space at [REF_PX]. */
    val spaceWidth: Float,
) {
    val lineHeight: Float = words.maxOfOrNull { it.height } ?: REF_PX * 1.2f
    val ascent: Float = words.maxOfOrNull { it.ascent } ?: REF_PX
    val totalWidth: Float = words.sumOf { it.width.toDouble() }.toFloat() + spaceWidth * (words.size - 1).coerceAtLeast(0)

    companion object {
        fun measure(line: LyricLine, measure: TextMeasure): MeasuredLine {
            val words = line.words.map { measure.measure(it.text, line) }
            val space = measure.measure("a a", line).width - measure.measure("aa", line).width
            return MeasuredLine(line, words, max(space, REF_PX * 0.2f))
        }
    }
}

/**
 * Where one word sits once settled. Coordinates are stage pixels; (x, y) is the top-left
 * of the word's box before rotation, and rotation is about the box centre.
 */
class WordPlacement(
    val index: Int,
    val x: Float,
    val y: Float,
    val fontPx: Float,
    val width: Float,
    val height: Float,
    val ascent: Float,
    val rotationDeg: Float = 0f,
    val row: Int = 0,
    /** Per-word random value in [-1, 1] for entrance variety. */
    val jitter: Float = 0f,
    /** Entrance direction sign used by sliding templates. */
    val side: Int = 1,
) {
    val centreX: Float get() = x + width / 2
    val centreY: Float get() = y + height / 2

    fun moved(dx: Float, dy: Float, s: Float, cx: Float, cy: Float): WordPlacement = WordPlacement(
        index,
        cx + (x - cx) * s + dx,
        cy + (y - cy) * s + dy,
        fontPx * s, width * s, height * s, ascent * s, rotationDeg, row, jitter, side,
    )

    /** Axis-aligned bounds of the rotated box. */
    fun bounds(): RectF {
        if (rotationDeg == 0f) return RectF(x, y, x + width, y + height)
        val a = Math.toRadians(rotationDeg.toDouble())
        val c = abs(cos(a)).toFloat()
        val s = abs(sin(a)).toFloat()
        val hw = (width * c + height * s) / 2
        val hh = (width * s + height * c) / 2
        return RectF(centreX - hw, centreY - hh, centreX + hw, centreY + hh)
    }
}

/** A connector stroke between two points (Graph template). Revealed when [revealWord] starts. */
class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val revealWord: Int) {
    fun moved(dx: Float, dy: Float, s: Float, cx: Float, cy: Float) =
        Segment(cx + (x0 - cx) * s + dx, cy + (y0 - cy) * s + dy, cx + (x1 - cx) * s + dx, cy + (y1 - cy) * s + dy, revealWord)
}

/** What a template produces for a line: settled word positions plus any decorations. */
class Arrangement(
    val words: List<WordPlacement>,
    val segments: List<Segment> = emptyList(),
) {
    fun bounds(): RectF {
        if (words.isEmpty()) return RectF.Empty
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (w in words) {
            val bb = w.bounds()
            l = min(l, bb.left); t = min(t, bb.top); r = max(r, bb.right); b = max(b, bb.bottom)
        }
        for (s in segments) {
            l = min(l, min(s.x0, s.x1)); r = max(r, max(s.x0, s.x1))
            t = min(t, min(s.y0, s.y1)); b = max(b, max(s.y0, s.y1))
        }
        return RectF(l, t, r, b)
    }

    /** Scale about the bounds centre by [s], then move that centre to ([cx], [cy]). */
    fun placed(s: Float, cx: Float, cy: Float): Arrangement {
        val bb = bounds()
        val dx = cx - bb.centreX
        val dy = cy - bb.centreY
        return Arrangement(
            words.map { it.moved(dx, dy, s, bb.centreX, bb.centreY) },
            segments.map { it.moved(dx, dy, s, bb.centreX, bb.centreY) },
        )
    }
}
