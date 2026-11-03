package com.lyricstage.core.stage

import kotlin.math.max
import kotlin.math.min

/**
 * Guarantees no word is ever clipped. For each line and template:
 *  1. shrink the font (binary search for the largest size that fits),
 *  2. if it would drop below a comfortable size, wrap onto more rows,
 *  3. rows are always width-balanced (see [Rows.balance]),
 *  4. last resort: scale the tightest arrangement down until it fits.
 */
object FitEngine {
    const val MARGIN_X = 0.06f
    const val MARGIN_TOP = 0.10f
    const val MARGIN_BOTTOM = 0.10f
    /** Share of the stage height kept for the romanised subtitle. */
    const val SUBTITLE_SHARE = 0.11f

    fun safeArea(stage: SizeF, hasSubtitle: Boolean): RectF {
        val bottomMargin = MARGIN_BOTTOM + if (hasSubtitle) SUBTITLE_SHARE else 0f
        return RectF(
            stage.width * MARGIN_X,
            stage.height * MARGIN_TOP,
            stage.width * (1 - MARGIN_X),
            stage.height * (1 - bottomMargin),
        )
    }

    fun maxFont(stage: SizeF): Float = min(stage.height * 0.20f, stage.width * 0.16f)

    /** Smallest size we shrink to before wrapping again; legible at arm's length. */
    fun minFont(stage: SizeF): Float = max(stage.minDimension * 0.05f, 12f)

    fun fit(template: LyricTemplate, line: MeasuredLine, stage: SizeF, seed: Int): TemplateLayout {
        val hasSub = !line.line.sub.isNullOrBlank()
        val area = safeArea(stage, hasSub)
        val subArea = if (hasSub) RectF(area.left, area.bottom + stage.height * 0.01f, area.right, area.bottom + stage.height * SUBTITLE_SHARE) else null
        if (line.words.isEmpty()) {
            return TemplateLayout(template.id, line.line, stage, Arrangement(emptyList()), area, true, seed, subArea)
        }
        val maxF = maxFont(stage)
        val minF = minFont(stage)
        val comfortable = max(minF, maxF * 0.42f)
        val options = template.rowOptions(line).ifEmpty { listOf(1) }

        for (floor in floatArrayOf(comfortable, minF)) {
            for (rows in options) {
                val f = largestFit(template, line, rows, area, seed, floor, maxF) ?: continue
                val arr = template.arrange(line, f, rows, seed).placed(1f, area.centreX, area.centreY)
                return TemplateLayout(template.id, line.line, stage, arr, area, verify(arr, area), seed, subArea)
            }
        }

        // last resort: the tightest arrangement, scaled down until it fits
        val rows = options.maxOrNull() ?: 1
        val raw = template.arrange(line, minF, rows, seed)
        val bb = raw.bounds()
        val s = min(area.width / bb.width.coerceAtLeast(1f), area.height / bb.height.coerceAtLeast(1f)) * 0.995f
        val arr = raw.placed(min(1f, s), area.centreX, area.centreY)
        return TemplateLayout(template.id, line.line, stage, arr, area, verify(arr, area), seed, subArea)
    }

    private fun fitsAt(template: LyricTemplate, line: MeasuredLine, rows: Int, area: RectF, seed: Int, f: Float): Boolean {
        val bb = template.arrange(line, f, rows, seed).bounds()
        return bb.width <= area.width && bb.height <= area.height
    }

    private fun largestFit(template: LyricTemplate, line: MeasuredLine, rows: Int, area: RectF, seed: Int, floor: Float, ceil: Float): Float? {
        if (!fitsAt(template, line, rows, area, seed, floor)) return null
        if (fitsAt(template, line, rows, area, seed, ceil)) return ceil
        var lo = floor
        var hi = ceil
        repeat(16) {
            val mid = (lo + hi) / 2
            if (fitsAt(template, line, rows, area, seed, mid)) lo = mid else hi = mid
        }
        // a hair smaller so the real (hinted) text measured at this size still fits
        return lo * 0.985f
    }

    /** Every word's rotated box inside the safe area. */
    fun verify(arr: Arrangement, area: RectF): Boolean = arr.words.all { area.contains(it.bounds()) }
}
