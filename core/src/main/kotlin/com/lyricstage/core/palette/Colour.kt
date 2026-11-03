package com.lyricstage.core.palette

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour helpers on packed ARGB ints (same layout as android.graphics.Color). */
object Colour {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    /** HSL with h in [0, 1). */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h /= 6f
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 1f) + 1f) % 1f * 360f
        val k = { n: Float -> (n + hh / 30f) % 12f }
        val a = s * min(l, 1 - l)
        val f = { n: Float -> l - a * max(-1f, min(k(n) - 3f, min(9f - k(n), 1f))) }
        return argb(255, (f(0f) * 255).toInt().coerceIn(0, 255), (f(8f) * 255).toInt().coerceIn(0, 255), (f(4f) * 255).toInt().coerceIn(0, 255))
    }

    /** WCAG relative luminance. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun hueDistance(a: Float, b: Float): Float = min(abs(a - b), 1f - abs(a - b))

    /** Darken [bg] (lowering HSL lightness) until [fg] on it reaches [minRatio]. */
    fun darkenForContrast(bg: Int, fg: Int, minRatio: Double): Int {
        if (contrast(bg, fg) >= minRatio) return bg
        val (h, s, l) = toHsl(bg)
        var lo = 0f
        var hi = l
        repeat(14) {
            val mid = (lo + hi) / 2
            if (contrast(fromHsl(h, s, mid), fg) >= minRatio) lo = mid else hi = mid
        }
        return fromHsl(h, s, lo)
    }

    fun withAlpha(c: Int, a: Float): Int = (((a.coerceIn(0f, 1f) * 255).toInt()) shl 24) or (c and 0xFFFFFF)
}
