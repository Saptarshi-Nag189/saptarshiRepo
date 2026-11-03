package com.lyricstage.core.lyrics

import com.lyricstage.core.text.Script

/** One sung word with absolute times in milliseconds. */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Inside parentheses: backing vocals. */
    val backing: Boolean = false,
    /** Held noticeably longer than the line's average word. */
    val emphasis: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)
}

data class LyricLine(
    val index: Int,
    val text: String,
    val startMs: Long,
    /** When the line leaves the stage. */
    val endMs: Long,
    val words: List<LyricWord>,
    val script: Script,
    /** Romanisation or translation that shared this line's timestamp. */
    val sub: String? = null,
    /** How many times this exact line appears in the song (chorus detection). */
    val repeats: Int = 1,
)

enum class LyricsKind { WORD_SYNCED, LINE_SYNCED, PLAIN, INSTRUMENTAL }

/** Everything the UI needs to show lyrics for one track. */
data class Lyrics(
    val kind: LyricsKind,
    val lines: List<LyricLine> = emptyList(),
    /** Unsynced text for [LyricsKind.PLAIN]. */
    val plainText: String = "",
) {
    val isSynced: Boolean get() = kind == LyricsKind.WORD_SYNCED || kind == LyricsKind.LINE_SYNCED

    /** Index of the line active at [positionMs]; -1 before the first line or between lines. */
    fun lineAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (ans >= 0 && positionMs >= lines[ans].endMs) return -1
        return ans
    }

    /** Last line that started at or before [positionMs], ignoring line ends. */
    fun lastStartedLine(positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        val Instrumental = Lyrics(LyricsKind.INSTRUMENTAL)
    }
}
