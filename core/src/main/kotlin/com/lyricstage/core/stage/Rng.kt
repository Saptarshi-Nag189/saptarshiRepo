package com.lyricstage.core.stage

/** Small deterministic PRNG (mulberry32) so layouts look random but are stable per seed. */
class Rng(seed: Int) {
    private var a: Int = if (seed == 0) 1 else seed

    fun next(): Float {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * next()

    /** Uniform in [-1, 1]. */
    fun signed(): Float = next() * 2f - 1f

    fun chance(p: Float): Boolean = next() < p

    companion object {
        /** FNV-1a, stable across runs (unlike String.hashCode on other platforms). */
        fun hash(s: String): Int {
            var h = -0x7ee3623b // 2166136261
            for (ch in s) {
                h = h xor ch.code
                h *= 16777619
            }
            return h
        }
    }
}
