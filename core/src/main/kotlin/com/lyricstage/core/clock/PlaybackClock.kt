package com.lyricstage.core.clock

import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns sparse, jittery player position reports into a smooth per-frame position.
 *
 * A media session reports `(position, lastPositionUpdateTime, speed)` only when something
 * changes. Between reports the position is extrapolated from the monotonic clock. When a
 * new report disagrees with what we were showing by less than [snapThresholdMs], the
 * difference is eased out over time instead of jumping, so words never flicker; larger
 * jumps (a seek, a track change) snap immediately.
 *
 * [now] must be a monotonic millisecond clock in the same base as the reports'
 * update times (`SystemClock.elapsedRealtime()` on Android).
 */
class PlaybackClock(
    private val now: () -> Long,
    private val snapThresholdMs: Long = 300,
    /**
     * Time constant of the easing. The correction decays as `e^(-t/tau)`, so its rate is at
     * most `threshold / tau` ms per ms. Keeping tau above the threshold (300/400 = 0.75)
     * means an eased backwards correction still never makes the position run backwards.
     */
    private val easeTauMs: Double = 400.0,
) {
    private var anchorPosMs = 0L
    private var anchorTimeMs = 0L
    private var speed = 1f
    private var playing = false

    /** Correction (shown minus raw) at the moment it was set; decays to zero. */
    private var errorMs = 0.0
    private var errorSetAt = 0L

    /** Per-track user offset, added after smoothing. Positive shows lyrics earlier. */
    var userOffsetMs: Long = 0

    val isPlaying: Boolean get() = playing

    /**
     * Re-anchor on every playback state or metadata change.
     * @param forceSnap true for a new track, where easing from the old one makes no sense.
     */
    fun anchor(positionMs: Long, updateTimeMs: Long, playbackSpeed: Float, isPlaying: Boolean, forceSnap: Boolean = false) {
        val t = now()
        val before = smoothedAt(t)
        anchorPosMs = positionMs
        anchorTimeMs = updateTimeMs
        speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        playing = isPlaying
        val after = rawAt(t)
        val delta = before - after
        if (forceSnap || abs(delta) >= snapThresholdMs) {
            errorMs = 0.0
        } else {
            errorMs = delta
            errorSetAt = t
        }
    }

    /** Extrapolated, unsmoothed position (for the debug screen). */
    fun rawPositionMs(): Long = rawAt(now()).toLong()

    /** Smoothed position without the user offset. */
    fun smoothedPositionMs(): Long = smoothedAt(now()).toLong()

    /** What the stage should show: smoothed position plus the per-song offset. */
    fun currentPositionMs(): Long = (smoothedAt(now()) + userOffsetMs).toLong().coerceAtLeast(0)

    private fun rawAt(t: Long): Double {
        if (!playing) return anchorPosMs.toDouble()
        val elapsed = (t - anchorTimeMs).coerceAtLeast(0)
        return anchorPosMs + elapsed * speed.toDouble()
    }

    private fun smoothedAt(t: Long): Double {
        val raw = rawAt(t)
        if (errorMs == 0.0) return raw
        val age = (t - errorSetAt).coerceAtLeast(0)
        val e = errorMs * exp(-age / easeTauMs)
        if (abs(e) < 0.5) {
            errorMs = 0.0
            return raw
        }
        return raw + e
    }
}
