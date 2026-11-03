package com.lyricstage.core.palette

/**
 * Colours for one track: a dark base for the background, three vivid accents for
 * highlighted words and the light field, and a text colour with safe contrast on the base.
 */
data class StagePalette(
    val base: Int,
    val accents: List<Int>,
    val text: Int,
) {
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Vivid system-like colours for tracks with no or grey artwork. */
        private val FALLBACK = intArrayOf(
            0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(),
            0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(),
        )

        fun fallback(seed: Int): StagePalette {
            val n = FALLBACK.size
            val s = Math.floorMod(seed, n)
            return StagePalette(
                base = 0xFF0B0B12.toInt(),
                accents = listOf(FALLBACK[s], FALLBACK[(s + 3) % n], FALLBACK[(s + 5) % n]),
                text = WHITE,
            )
        }
    }
}

/** A colour sampled from artwork and how much of the image it covers. */
data class Swatch(val rgb: Int, val population: Int)

/** Builds a [StagePalette] from artwork swatches. Pure maths; the app feeds AndroidX Palette output. */
object PaletteBuilder {
    /** Minimum contrast of text on the base: WCAG AAA for body text. */
    const val MIN_CONTRAST = 7.0

    fun build(swatches: List<Swatch>, seed: Int): StagePalette {
        if (swatches.isEmpty()) return StagePalette.fallback(seed)
        val scored = swatches.mapNotNull { sw ->
            val (h, s, l) = Colour.toHsl(sw.rgb)
            if (s < 0.2f || l < 0.12f || l > 0.92f) null
            else Triple(sw, floatArrayOf(h, s, l), sw.population * s * (1f - kotlin.math.abs(l - 0.55f)))
        }.sortedByDescending { it.third }

        // up to three hues that are visibly different from each other
        val chosen = ArrayList<FloatArray>()
        for ((_, hsl, _) in scored) {
            if (chosen.all { Colour.hueDistance(it[0], hsl[0]) > 0.07f }) chosen += hsl
            if (chosen.size == 3) break
        }
        val fb = StagePalette.fallback(seed)
        if (chosen.isEmpty()) return fb.copy(base = darkBase(swatches, null))

        // vivid: boost saturation, keep lightness readable on dark
        val accents = (0 until 3).map { i ->
            chosen.getOrNull(i)?.let { (h, s, l) ->
                Colour.fromHsl(h, (s * 1.25f).coerceIn(0.65f, 1f), l.coerceIn(0.55f, 0.7f))
            } ?: fb.accents[i]
        }
        val base = darkBase(swatches, chosen[0][0])
        val text = StagePalette.WHITE
        return StagePalette(Colour.darkenForContrast(base, text, MIN_CONTRAST), accents, text)
    }

    /** Average hue of the darker swatches, at very low lightness. */
    private fun darkBase(swatches: List<Swatch>, fallbackHue: Float?): Int {
        val dark = swatches.map { it to Colour.toHsl(it.rgb) }.filter { it.second[2] < 0.35f }
        return if (dark.isNotEmpty()) {
            val pop = dark.sumOf { it.first.population }.coerceAtLeast(1).toFloat()
            // hue is circular: average it as a vector
            var x = 0.0
            var y = 0.0
            var s = 0f
            for ((sw, hsl) in dark) {
                val w = sw.population / pop
                x += kotlin.math.cos(hsl[0] * 2 * Math.PI) * w
                y += kotlin.math.sin(hsl[0] * 2 * Math.PI) * w
                s += hsl[1] * w
            }
            val h = ((kotlin.math.atan2(y, x) / (2 * Math.PI)) + 1.0).toFloat() % 1f
            Colour.fromHsl(h, s.coerceAtMost(0.5f), 0.07f)
        } else {
            Colour.fromHsl(fallbackHue ?: 0.66f, 0.4f, 0.07f)
        }
    }
}
