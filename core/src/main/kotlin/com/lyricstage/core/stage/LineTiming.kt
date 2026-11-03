package com.lyricstage.core.stage

import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.lyrics.LyricWord
import com.lyricstage.core.lyrics.Lyrics

/** Timing rules shared by every template. */
object LineTiming {
    /** Quick exit: the line fades and lifts away over this long before its end. */
    const val EXIT_MS = 220L

    fun enterAtMs(line: LyricLine): Long = (line.words.firstOrNull()?.startMs ?: line.startMs) - ENTRY_LEAD_MS

    fun wordEnterAtMs(word: LyricWord): Long = word.startMs - ENTRY_LEAD_MS

    /** 0 while the line is fully shown, rising to 1 as it leaves. */
    fun exitProgress(line: LyricLine, positionMs: Long): Float =
        ((positionMs - (line.endMs - EXIT_MS)).toFloat() / EXIT_MS).coerceIn(0f, 1f)

    /** Left-to-right fill of a word across its sung duration. */
    fun fill(word: LyricWord, positionMs: Long): Float =
        ((positionMs - word.startMs).toFloat() / word.durationMs).coerceIn(0f, 1f)

    /** The line to draw at [positionMs], or -1 for none (before lyrics, instrumental gap). */
    fun visibleLine(lyrics: Lyrics, positionMs: Long): Int {
        val i = lyrics.lastStartedLine(positionMs + ENTRY_LEAD_MS)
        if (i < 0) return -1
        return if (positionMs < lyrics.lines[i].endMs) i else -1
    }
}

/** A soft ripple to emit in the background where a word was just sung. */
class Ripple(val x: Float, val y: Float, val strength: Float, val newLine: Boolean)

/**
 * Detects words whose start time was crossed since the last frame. Large jumps (seeks)
 * reset it without emitting, so a seek never triggers a burst of ripples.
 */
class RippleTracker(private val maxStepMs: Long = 500) {
    private var lastPos = Long.MIN_VALUE

    fun reset() {
        lastPos = Long.MIN_VALUE
    }

    fun update(plan: StagePlan, positionMs: Long, out: MutableList<Ripple>) {
        val prev = lastPos
        lastPos = positionMs
        if (prev == Long.MIN_VALUE || positionMs <= prev || positionMs - prev > maxStepMs) return
        val li = plan.lyrics.lastStartedLine(positionMs)
        if (li < 0) return
        val line = plan.lines[li]
        val layout = plan.layouts[li]
        for (w in layout.words) {
            val word = line.words[w.index]
            if (word.startMs in (prev + 1)..positionMs) {
                out += Ripple(
                    x = w.centreX / plan.stage.width,
                    y = w.centreY / plan.stage.height,
                    strength = if (w.index in plan.highlights[li]) 1f else 0.6f,
                    newLine = w.index == 0,
                )
            }
        }
    }
}
