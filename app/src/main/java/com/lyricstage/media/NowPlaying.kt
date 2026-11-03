package com.lyricstage.media

import android.graphics.Bitmap
import com.lyricstage.core.lyrics.TrackKey

/** Snapshot of the active media session. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** Null when the player does not report a duration. */
    val durationMs: Long?,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** `SystemClock.elapsedRealtime()` of [positionMs]. */
    val positionUpdateTimeMs: Long,
    val speed: Float,
    val canSeek: Boolean,
) {
    val trackKey: String = TrackKey.of(artist, title, durationMs)
    val hasTrack: Boolean get() = title.isNotBlank()
}
