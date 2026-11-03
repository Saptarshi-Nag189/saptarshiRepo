package com.lyricstage

import android.content.Context
import com.lyricstage.core.lyrics.LrclibClient
import com.lyricstage.lyrics.LyricsRepository
import com.lyricstage.lyrics.db.LyricsDatabase
import com.lyricstage.media.ActiveSessionTracker
import com.lyricstage.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies. Small enough not to need Hilt. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    val database = LyricsDatabase.create(context)
    val lrclib = LrclibClient(http)
    val lyrics = LyricsRepository(database.lyricsDao(), database.offsetDao(), lrclib)
    val settings = SettingsStore(context)
    val sessions = ActiveSessionTracker(context)
}
