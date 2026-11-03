package com.lyricstage.stage

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import com.lyricstage.core.stage.Ripple
import com.lyricstage.core.stage.RippleTracker

/**
 * Per-frame values written once by the frame loop and read by the draw passes. Only
 * [tick] and [blur] are snapshot state, so a frame invalidates drawing and layer
 * properties, never composition.
 */
class StageFrame {
    val tick = mutableLongStateOf(0L)
    val blur = mutableFloatStateOf(0f)
    var positionMs = 0L
    var line = -1

    val tracker = RippleTracker()
    val ripples = ArrayList<Ripple>(8)

    /** Frames counted over the last full second. */
    val fps = mutableIntStateOf(0)
    private var fpsWindowStart = 0L
    private var fpsCount = 0

    fun countFrame(nanos: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = nanos
        fpsCount++
        val elapsed = nanos - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            fps.intValue = (fpsCount * 1_000_000_000L / elapsed).toInt()
            fpsCount = 0
            fpsWindowStart = nanos
        }
    }
}
