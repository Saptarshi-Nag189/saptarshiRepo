package com.lyricstage.core.stage

import com.lyricstage.core.lyrics.LyricLine

enum class TemplateId(val label: String) {
    STACK("Stack"), SLIDE("Slide"), ZOOM("Zoom"), LETTERS("Letters"), SCATTER("Scatter"), GRAPH("Graph"), FLOW("Flow");

    companion object {
        fun fromName(name: String?): TemplateId? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Transform applied to a word (or glyph) relative to its settled placement. Mutable and
 * reused every frame so drawing allocates nothing.
 */
class Pose {
    var dx = 0f
    var dy = 0f
    var scaleX = 1f
    var scaleY = 1f
    var rotationDeg = 0f
    var skewX = 0f
    var alpha = 1f
    var blurPx = 0f

    fun reset(): Pose {
        dx = 0f; dy = 0f; scaleX = 1f; scaleY = 1f; rotationDeg = 0f; skewX = 0f; alpha = 1f; blurPx = 0f
        return this
    }

    val isIdentity: Boolean
        get() = dx == 0f && dy == 0f && scaleX == 1f && scaleY == 1f && rotationDeg == 0f && skewX == 0f && blurPx == 0f
}

/** A fitted line: settled positions inside the stage, ready to draw. */
class TemplateLayout(
    val template: TemplateId,
    val line: LyricLine,
    val stage: SizeF,
    val arrangement: Arrangement,
    /** Safe area the words were fitted into. */
    val safeArea: RectF,
    /** False only if even the last-resort shrink could not fit (should never happen). */
    val fits: Boolean,
    val seed: Int,
    /** Strip under the words for the romanised subtitle, when the line has one. */
    val subtitleArea: RectF?,
) {
    val words: List<WordPlacement> get() = arrangement.words
    val segments: List<Segment> get() = arrangement.segments
}

/**
 * A kinetic layout style. Geometry and motion are pure functions so they can be tested on
 * the JVM and evaluated for any playback position; the app's renderer draws them.
 */
interface LyricTemplate {
    val id: TemplateId

    /** Whole line visible from its start with upcoming words dimmed (instead of entering one by one). */
    val showsUpcoming: Boolean get() = false

    /** Animates each glyph separately (uses [glyphPose]). */
    val perGlyph: Boolean get() = false

    /** Row counts worth trying, in order of preference. */
    fun rowOptions(line: MeasuredLine): List<Int>

    /** Settled word positions for a font size and row count, in any coordinate origin. */
    fun arrange(line: MeasuredLine, fontPx: Float, rows: Int, seed: Int): Arrangement

    /** Entrance pose [sinceEnterMs] after the word starts entering. */
    fun pose(p: WordPlacement, sinceEnterMs: Long, stage: SizeF, out: Pose)

    /** Entrance pose of one glyph. Only called when [perGlyph]. */
    fun glyphPose(p: WordPlacement, glyph: Int, sinceEnterMs: Long, out: Pose) {
        out.reset()
    }

    /** Fit [line] into [bounds]: shrink, then wrap, then rebalance. */
    fun layout(line: MeasuredLine, bounds: SizeF, seed: Int, fontScale: Float = 1f): TemplateLayout =
        FitEngine.fit(this, line, bounds, seed, fontScale)
}

enum class Align { START, CENTRE, END }

/**
 * Shared row arrangement: words split into balanced rows, baseline aligned within each row.
 * [scales] sizes words individually; [indent] shifts rows horizontally (in font px units).
 */
internal fun arrangeRows(
    line: MeasuredLine,
    fontPx: Float,
    rows: Int,
    align: Align,
    scales: FloatArray? = null,
    leading: Float = 1.02f,
    gapEm: Float = 1f,
    indent: (row: Int, rowCount: Int) -> Float = { _, _ -> 0f },
    side: (row: Int, word: Int) -> Int = { _, _ -> 1 },
    jitter: Rng? = null,
): Arrangement {
    val k = fontPx / REF_PX
    val n = line.words.size
    val scale = { i: Int -> scales?.get(i) ?: 1f }
    val widths = List(n) { line.words[it].width * k * scale(it) }
    val gap = line.spaceWidth * k * gapEm
    val parts = Rows.balance(widths, rows, gap)
    val rowWidths = parts.map { r -> r.sumOf { widths[it].toDouble() }.toFloat() + gap * (r.count() - 1) }
    val maxW = rowWidths.maxOrNull() ?: 0f
    val out = ArrayList<WordPlacement>(n)
    var top = 0f
    for ((ri, range) in parts.withIndex()) {
        val rowAscent = range.maxOf { line.words[it].ascent * k * scale(it) }
        val rowDescent = range.maxOf { line.words[it].descent * k * scale(it) }
        var x = when (align) {
            Align.START -> 0f
            Align.CENTRE -> (maxW - rowWidths[ri]) / 2
            Align.END -> maxW - rowWidths[ri]
        } + indent(ri, parts.size) * fontPx
        for (i in range) {
            val m = line.words[i]
            val s = scale(i)
            out += WordPlacement(
                index = i,
                x = x,
                y = top + rowAscent - m.ascent * k * s,
                fontPx = fontPx * s,
                width = widths[i],
                height = m.height * k * s,
                ascent = m.ascent * k * s,
                row = ri,
                jitter = jitter?.signed() ?: 0f,
                side = side(ri, i),
            )
            x += widths[i] + gap
        }
        top += (rowAscent + rowDescent) * leading
    }
    return Arrangement(out)
}

/** Default rows for [n] words: about two words per row, at most [max]. */
internal fun defaultRows(n: Int, max: Int): List<Int> {
    val preferred = ((n + 1) / 2).coerceIn(1, max)
    return (preferred..max.coerceAtMost(n).coerceAtLeast(preferred)).toList() +
        (1 until preferred).reversed()
}

/** Common entrance timing: words start entering this long before they are sung. */
const val ENTRY_LEAD_MS = 160L
