package com.lyricstage.core.stage

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closed-form damped spring, evaluated from elapsed time alone, so the stage can be drawn
 * for any position (seeks, scrubbing) without per-frame state.
 *
 * For an under-damped spring released from 0 towards 1 (unit mass):
 *   w0 = sqrt(stiffness), wd = w0 * sqrt(1 - z^2)
 *   x(t) = 1 - e^(-z w0 t) (cos(wd t) + (z w0 / wd) sin(wd t))
 * Once the envelope falls below [EPSILON] the value is exactly 1, so settled text is crisp.
 */
class Spring(val dampingRatio: Float = 0.6f, val stiffness: Float = 380f) {
    private val w0 = sqrt(stiffness.toDouble())
    private val z = dampingRatio.toDouble().coerceIn(0.05, 0.999)
    private val wd = w0 * sqrt(1 - z * z)
    private val envScale = 1 + z * w0 / wd

    /** Time after which the spring counts as settled. */
    val settleMs: Long = (ln(envScale / EPSILON) / (z * w0) * 1000).toLong()

    /** Progress from 0 to 1 (overshooting) [ms] after release. */
    fun value(ms: Long): Float {
        if (ms <= 0) return 0f
        if (ms >= settleMs) return 1f
        val t = ms / 1000.0
        val e = exp(-z * w0 * t)
        return (1 - e * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))).toFloat()
    }

    companion object {
        const val EPSILON = 0.001
        val Bouncy = Spring(0.6f, 380f)
        val Soft = Spring(0.75f, 160f)
    }
}

/** Ease-out cubic for fades and exits. */
fun easeOut(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    val u = 1 - c
    return 1 - u * u * u
}
