package com.lyricstage.core

import com.lyricstage.core.clock.PlaybackClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    private var now = 0L
    private val clock = PlaybackClock({ now })

    @Test fun extrapolatesWhilePlaying() {
        now = 1_000
        clock.anchor(5_000, updateTimeMs = 1_000, playbackSpeed = 1f, isPlaying = true, forceSnap = true)
        now = 1_500
        assertEquals(5_500, clock.currentPositionMs())
        clock.anchor(5_500, 1_500, 1f, isPlaying = false)
        now = 3_000
        assertEquals(5_500, clock.rawPositionMs())
    }

    @Test fun easesSmallDriftWithoutRunningBackwards() {
        now = 0
        clock.anchor(10_000, 0, 1f, true, forceSnap = true)
        now = 1_000
        // player says we are 200 ms behind what we show
        clock.anchor(10_800, 1_000, 1f, true)
        var last = clock.currentPositionMs()
        assertEquals(11_000, last)
        while (now < 4_000) {
            now += 16
            val p = clock.currentPositionMs()
            assertTrue("went backwards at $now: $last -> $p", p >= last)
            last = p
        }
        assertEquals(10_800 + 3_008L, clock.smoothedPositionMs(), 2.0.toLong().toDouble())
    }

    @Test fun snapsOnSeek() {
        now = 0
        clock.anchor(10_000, 0, 1f, true, forceSnap = true)
        now = 100
        clock.anchor(60_000, 100, 1f, true)
        assertEquals(60_000, clock.currentPositionMs())
    }

    @Test fun userOffsetAppliedAfterSmoothing() {
        now = 0
        clock.anchor(10_000, 0, 1f, false, forceSnap = true)
        clock.userOffsetMs = 250
        assertEquals(10_250, clock.currentPositionMs())
        assertEquals(10_000, clock.smoothedPositionMs())
    }

    @Test fun honoursPlaybackSpeed() {
        now = 0
        clock.anchor(0, 0, 2f, true, forceSnap = true)
        now = 1_000
        assertEquals(2_000, clock.currentPositionMs())
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)

    private fun assertEquals(expected: Long, actual: Long) = org.junit.Assert.assertEquals(expected, actual)
}
