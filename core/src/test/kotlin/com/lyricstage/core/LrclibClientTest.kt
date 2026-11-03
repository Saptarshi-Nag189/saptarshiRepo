package com.lyricstage.core

import com.lyricstage.core.lyrics.LrclibClient
import com.lyricstage.core.lyrics.LyricsLookup
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibClientTest {
    private val server = MockWebServer()
    private val client by lazy { LrclibClient(OkHttpClient(), server.url("/api/")) }

    @After fun tearDown() = server.shutdown()

    @Test fun exactHitWithSyncedLyrics() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":1,"trackName":"Song","artistName":"A","duration":200,"instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00]x"}"""))
        val r = client.find("Song (Official Video)", "A feat. B", "Album", 200_400)
        assertEquals(LyricsLookup.Found(true, "[00:01.00]x", false), r)
        val req = server.takeRequest()
        assertEquals("/api/get?track_name=Song&artist_name=A&album_name=Album&duration=200", req.path)
        assertTrue(req.getHeader("User-Agent")!!.startsWith("LyricStage/"))
    }

    @Test fun fallsBackToSearchPreferringSyncedAndCloseDuration() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setBody(
                """[{"id":1,"duration":300,"syncedLyrics":"[00:01.00]far"},
                   {"id":2,"duration":201,"plainLyrics":"plain"},
                   {"id":3,"duration":203,"syncedLyrics":"[00:01.00]near"}]""",
            ),
        )
        val r = client.find("Song", "A", null, 200_000)
        assertEquals(LyricsLookup.Found(true, "[00:01.00]near", false), r)
        server.takeRequest()
        assertEquals("/api/search?q=A%20Song", server.takeRequest().path)
    }

    @Test fun notFound() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(LyricsLookup.NotFound, client.find("Song", "A", null, null))
    }

    @Test fun unavailableAfterRetries() = runTest {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertEquals(LyricsLookup.Unavailable, client.find("Song", "A", null, null))
    }
}
