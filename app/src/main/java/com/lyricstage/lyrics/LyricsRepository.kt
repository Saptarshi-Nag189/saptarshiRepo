package com.lyricstage.lyrics

import com.lyricstage.core.lyrics.LrcParser
import com.lyricstage.core.lyrics.LrclibClient
import com.lyricstage.core.lyrics.Lyrics
import com.lyricstage.core.lyrics.LyricsBuilder
import com.lyricstage.core.lyrics.LyricsLookup
import com.lyricstage.lyrics.db.LyricsDao
import com.lyricstage.lyrics.db.LyricsEntity
import com.lyricstage.lyrics.db.OffsetDao
import com.lyricstage.lyrics.db.OffsetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the stage knows about the current track's lyrics. */
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val lyrics: Lyrics, val source: String) : LyricsState
    data object NotFound : LyricsState
    data object Unavailable : LyricsState
}

/** The fields of a track that lyric lookups need. */
data class TrackQuery(val key: String, val title: String, val artist: String, val album: String?, val durationMs: Long?)

class LyricsRepository(
    private val dao: LyricsDao,
    private val offsets: OffsetDao,
    private val client: LrclibClient,
) {
    /**
     * Lyrics for [track], from the cache when possible. Missing lyrics are retried every
     * [RETRY_MS] while [isPlaying]. A manual import updates the flow immediately.
     */
    fun observe(track: TrackQuery, isPlaying: StateFlow<Boolean>): Flow<LyricsState> = channelFlow {
        send(LyricsState.Loading)
        launch {
            while (true) {
                if (dao.get(track.key) != null) break
                isPlaying.first { it }
                when (val r = client.find(track.title, track.artist, track.album, track.durationMs)) {
                    is LyricsLookup.Found -> {
                        dao.upsert(entity(track, r.raw, r.synced, r.instrumental, SOURCE_LRCLIB))
                        break
                    }
                    LyricsLookup.NotFound -> send(LyricsState.NotFound)
                    LyricsLookup.Unavailable -> send(LyricsState.Unavailable)
                }
                delay(RETRY_MS)
            }
        }
        dao.observe(track.key).collect { e ->
            if (e != null) send(LyricsState.Ready(toLyrics(e, track.durationMs), e.source))
        }
    }.flowOn(Dispatchers.Default)

    /** Bind a user-picked .lrc (or plain text) to [track]. Returns false if it is empty. */
    suspend fun import(track: TrackQuery, raw: String): Boolean {
        val text = raw.removePrefix("﻿").trim()
        if (text.isEmpty()) return false
        dao.upsert(entity(track, text, LrcParser.isSynced(text), instrumental = false, source = SOURCE_IMPORT))
        return true
    }

    fun offset(key: String): Flow<Long> = offsets.observe(key).map { it ?: 0L }

    suspend fun setOffset(key: String, offsetMs: Long) = offsets.upsert(OffsetEntity(key, offsetMs))

    suspend fun cached(): List<LyricsEntity> = dao.all()

    private fun entity(t: TrackQuery, raw: String, synced: Boolean, instrumental: Boolean, source: String) =
        LyricsEntity(t.key, raw, synced, instrumental, source, t.title, t.artist, t.durationMs, System.currentTimeMillis())

    companion object {
        const val RETRY_MS = 60_000L
        const val SOURCE_LRCLIB = "lrclib"
        const val SOURCE_IMPORT = "import"

        fun toLyrics(e: LyricsEntity, durationMs: Long?): Lyrics = when {
            e.instrumental -> Lyrics.Instrumental
            e.synced || LrcParser.isSynced(e.raw) -> LyricsBuilder.fromLrc(e.raw, durationMs ?: e.durationMs)
            else -> LyricsBuilder.plain(e.raw)
        }
    }
}
