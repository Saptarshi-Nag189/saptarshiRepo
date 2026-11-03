package com.lyricstage.core.stage

import kotlin.math.max

/** Splitting words into rows of similar width. */
object Rows {
    /**
     * Optimal linear partition: split [widths] (in order) into exactly min(rows, n) rows,
     * minimising the widest row. [gap] is added between words in a row.
     */
    fun balance(widths: List<Float>, rows: Int, gap: Float): List<IntRange> {
        val n = widths.size
        if (n == 0) return emptyList()
        val k = rows.coerceIn(1, n)
        if (k == 1) return listOf(0 until n)
        val prefix = FloatArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + widths[i]
        fun rowWidth(from: Int, to: Int) = prefix[to] - prefix[from] + gap * (to - from - 1)

        // best[j][i]: smallest possible widest row when the first i words form j rows
        val best = Array(k + 1) { FloatArray(n + 1) { Float.POSITIVE_INFINITY } }
        val cut = Array(k + 1) { IntArray(n + 1) }
        best[0][0] = 0f
        for (j in 1..k) {
            for (i in j..n) {
                for (p in (j - 1) until i) {
                    val v = max(best[j - 1][p], rowWidth(p, i))
                    if (v < best[j][i]) {
                        best[j][i] = v
                        cut[j][i] = p
                    }
                }
            }
        }
        val out = ArrayList<IntRange>(k)
        var i = n
        for (j in k downTo 1) {
            val p = cut[j][i]
            out += p until i
            i = p
        }
        out.reverse()
        return out
    }
}
