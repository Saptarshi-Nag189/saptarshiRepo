package com.lyricstage.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lyricstage.core.clock.PlaybackClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the device's active media sessions, picks the one to show (playing, most recently
 * active) and keeps [clock] anchored to its position reports. All callbacks run on the main
 * thread.
 */
class ActiveSessionTracker(private val context: Context) {
    private val manager: MediaSessionManager = requireNotNull(context.getSystemService(MediaSessionManager::class.java))
    private val component = ComponentName(context, LyricListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    val clock = PlaybackClock(SystemClock::elapsedRealtime)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _accessDenied = MutableStateFlow(false)
    val accessDenied: StateFlow<Boolean> = _accessDenied.asStateFlow()

    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastActiveAt = HashMap<String, Long>()
    private var active: MediaController? = null
    private var started = false
    private var artCache: Pair<String, Bitmap?>? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list.orEmpty())
    }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            _accessDenied.value = false
            onSessionsChanged(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not granted", e)
            _accessDenied.value = true
        }
    }

    fun stop() {
        if (!started) return
        manager.removeOnActiveSessionsChangedListener(sessionsListener)
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        started = false
    }

    // Transport controls on the shown session.
    fun play() = active?.transportControls?.play()
    fun pause() = active?.transportControls?.pause()
    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = active?.transportControls?.seekTo(ms)

    private fun onSessionsChanged(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val gone = callbacks.keys.filter { it.sessionToken !in tokens }
        for (c in gone) callbacks.remove(c)?.let(c::unregisterCallback)
        for (c in list) {
            if (callbacks.keys.any { it.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING) lastActiveAt[c.packageName] = SystemClock.elapsedRealtime()
                    choose()
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (c.sessionToken == active?.sessionToken) publish()
                }
                override fun onSessionDestroyed() {
                    callbacks.remove(c)?.let(c::unregisterCallback)
                    choose()
                }
            }
            c.registerCallback(cb, handler)
            callbacks[c] = cb
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                lastActiveAt.putIfAbsent(c.packageName, SystemClock.elapsedRealtime())
            }
        }
        choose()
    }

    /** Playing sessions win, most recently active first; otherwise keep what we showed. */
    private fun choose() {
        val all = callbacks.keys.toList()
        val playing = all.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val pick = playing.maxByOrNull { lastActiveAt[it.packageName] ?: 0L }
            ?: all.firstOrNull { it.sessionToken == active?.sessionToken }
            ?: all.maxByOrNull { lastActiveAt[it.packageName] ?: 0L }
        active = pick
        publish()
    }

    private fun publish() {
        val c = active
        if (c == null) {
            _nowPlaying.value = null
            return
        }
        val meta = c.metadata
        val state = c.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.position?.coerceAtLeast(0) ?: 0L
        val updateTime = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f

        val np = NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            art = artFor(c.packageName + "|" + title + "|" + artist, meta),
            isPlaying = isPlaying,
            positionMs = position,
            positionUpdateTimeMs = updateTime,
            speed = speed,
            canSeek = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
        )
        val trackChanged = _nowPlaying.value?.trackKey != np.trackKey
        // re-anchor on every state or metadata change
        clock.anchor(position, updateTime, speed, isPlaying, forceSnap = trackChanged)
        _nowPlaying.value = np
    }

    /** Artwork from the metadata bitmap, or a local content URI. Never fetched from the web. */
    private fun artFor(key: String, meta: MediaMetadata?): Bitmap? {
        artCache?.let { (k, bmp) -> if (k == key && bmp != null) return bmp }
        val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: localArt(meta)
        artCache = key to bmp
        return bmp
    }

    private fun localArt(meta: MediaMetadata?): Bitmap? {
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { meta?.getString(it) }?.let(Uri::parse) ?: return null
        if (uri.scheme != "content" && uri.scheme != "android.resource" && uri.scheme != "file") return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val max = 512
                val w = info.size.width
                val h = info.size.height
                if (w > max || h > max) {
                    val s = max.toFloat() / maxOf(w, h)
                    decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "No local artwork: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SessionTracker"
    }
}
