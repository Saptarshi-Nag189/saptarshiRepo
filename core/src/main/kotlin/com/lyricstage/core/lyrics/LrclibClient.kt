package com.lyricstage.core.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToLong

@Serializable
data class LrclibRecord(
    val id: Long = 0,
    @SerialName("trackName") val trackName: String = "",
    @SerialName("artistName") val artistName: String = "",
    @SerialName("albumName") val albumName: String? = null,
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

/** What a lookup found. */
sealed interface LyricsLookup {
    /** Raw LRC or plain text, ready for [LyricsBuilder]. */
    data class Found(val synced: Boolean, val raw: String, val instrumental: Boolean) : LyricsLookup
    data object NotFound : LyricsLookup
    /** LRCLIB itself failed (network, 5xx): worth retrying later. */
    data object Unavailable : LyricsLookup
}

/** LRCLIB client. The only network access the app makes. */
class LrclibClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://lrclib.net/api/".toHttpUrl(),
    private val userAgent: String = USER_AGENT,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class UpstreamDown : IOException("LRCLIB unavailable")

    suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): LyricsLookup {
        val cleanTitle = TitleCleaner.cleanTitle(title)
        val cleanArtist = TitleCleaner.cleanArtist(artist)
        val durationSec = durationMs?.takeIf { it > 0 }?.let { (it / 1000.0).roundToLong() }

        // 1. exact lookup
        var exactDown = false
        val exact: LrclibRecord? = try {
            val url = baseUrl.newBuilder().addPathSegment("get")
                .addQueryParameter("track_name", cleanTitle)
                .addQueryParameter("artist_name", cleanArtist)
                .apply {
                    if (!album.isNullOrBlank()) addQueryParameter("album_name", album)
                    if (durationSec != null) addQueryParameter("duration", durationSec.toString())
                }.build()
            getJson(url)?.let { json.decodeFromString(LrclibRecord.serializer(), it) }
        } catch (e: UpstreamDown) {
            exactDown = true
            null
        }
        val exactResult = exact?.let(::toResult)
        if (exactResult != null && (exactResult.synced || exactResult.instrumental)) return exactResult

        // 2. fuzzy search; prefer synced results closest in length to the playing track
        val results: List<LrclibRecord> = try {
            val url = baseUrl.newBuilder().addPathSegment("search")
                .addQueryParameter("q", "$cleanArtist $cleanTitle")
                .build()
            getJson(url)?.let { json.decodeFromString<List<LrclibRecord>>(it) } ?: emptyList()
        } catch (e: UpstreamDown) {
            return exactResult ?: LyricsLookup.Unavailable
        }
        if (exactDown && results.isEmpty()) return LyricsLookup.Unavailable

        val candidates = results
            .filter { durationSec == null || abs(it.duration - durationSec) <= 8 }
            .sortedWith(
                compareBy<LrclibRecord> { if (it.syncedLyrics.isNullOrBlank()) 1 else 0 }
                    .thenBy { if (durationSec == null) 0.0 else abs(it.duration - durationSec) },
            )
        for (c in candidates) toResult(c)?.let { return it }
        return exactResult ?: LyricsLookup.NotFound
    }

    private fun toResult(rec: LrclibRecord): LyricsLookup.Found? = when {
        rec.instrumental -> LyricsLookup.Found(synced = false, raw = "", instrumental = true)
        !rec.syncedLyrics.isNullOrBlank() -> LyricsLookup.Found(synced = true, raw = rec.syncedLyrics, instrumental = false)
        !rec.plainLyrics.isNullOrBlank() -> LyricsLookup.Found(synced = false, raw = rec.plainLyrics, instrumental = false)
        else -> null
    }

    /** Body text, null on 404, [UpstreamDown] after retries on errors. */
    private suspend fun getJson(url: HttpUrl, attempts: Int = 3): String? = withContext(Dispatchers.IO) {
        repeat(attempts) { attempt ->
            try {
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                http.newCall(req).execute().use { res ->
                    if (res.code == 404) return@withContext null
                    if (res.isSuccessful) return@withContext res.body?.string()
                }
            } catch (_: IOException) {
                // network error or timeout: retry
            }
            delay(400L * (attempt + 1))
        }
        throw UpstreamDown()
    }

    companion object {
        const val USER_AGENT = "LyricStage/0.1 (personal Android lyrics viewer; https://github.com/Saptarshi-Nag189/saptarshiRepo)"
    }
}
