package com.lyricstage.ui.debug

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricstage.R
import com.lyricstage.core.lyrics.Lyrics
import com.lyricstage.core.lyrics.LyricsBuilder
import com.lyricstage.core.palette.StagePalette
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.lyrics.LyricsRepository
import com.lyricstage.stage.PreparedStage
import com.lyricstage.stage.StageColours
import com.lyricstage.stage.StageLayer
import com.lyricstage.stage.StageRenderer
import com.lyricstage.stage.advance
import com.lyricstage.ui.StageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Song(val name: String, val lyrics: Lyrics)

private class FitResult(val checked: Int, val failures: List<String>, val songs: List<Song>)

/** Stage sizes to check, in dp: a typical phone in portrait and landscape, plus a small one. */
private val SIZES_DP = listOf("phone" to (411f to 891f), "landscape" to (891f to 411f), "small" to (360f to 640f), "small landscape" to (640f to 360f))

/**
 * Renders every line of every loaded song (cached lyrics plus bundled samples) in every
 * template at phone and landscape sizes, with the real fonts, and lists any word that
 * would be clipped. Expect 0.
 */
@Composable
fun FitCheckScreen(vm: StageViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val result by produceState<FitResult?>(null) {
        value = withContext(Dispatchers.Default) {
            val songs = loadSongs(context, vm)
            run(songs, TextMeasurer(resolver, density, LayoutDirection.Ltr, 0), density)
        }
    }
    DebugScaffold(stringResource(R.string.debug_fitcheck), onBack) {
        val r = result
        if (r == null) {
            Text(stringResource(R.string.fitcheck_running), color = Color.White)
            return@DebugScaffold
        }
        Text(
            stringResource(R.string.fitcheck_summary, r.checked, r.failures.size),
            color = if (r.failures.isEmpty()) Color(0xFF30D158) else Color(0xFFFF375F),
            fontSize = 18.sp,
        )
        if (r.failures.isEmpty()) Text(stringResource(R.string.fitcheck_ok), color = Color.White.copy(alpha = 0.7f))
        Preview(r.songs, density, resolver)
        LazyColumn {
            items(r.failures) { f -> Text(f, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}

private suspend fun loadSongs(context: Context, vm: StageViewModel): List<Song> {
    val samples = context.assets.list("samples").orEmpty().sorted().map { file ->
        val raw = context.assets.open("samples/$file").bufferedReader().use { it.readText() }
        Song("sample: $file", LyricsBuilder.fromLrc(raw, 180_000))
    }
    val cached = vm.cachedSongs().map { e -> Song("${e.artist} - ${e.title}", LyricsRepository.toLyrics(e, e.durationMs)) }
    return (samples + cached).filter { it.lyrics.lines.isNotEmpty() }
}

private fun run(songs: List<Song>, measurer: TextMeasurer, density: Density): FitResult {
    val failures = ArrayList<String>()
    var checked = 0
    for ((sizeName, dp) in SIZES_DP) {
        val stage = with(density) { SizeF(dp.first.dp.toPx(), dp.second.dp.toPx()) }
        for (song in songs) for (t in TemplateId.entries) {
            val prep = PreparedStage.prepare(song.lyrics, stage, measurer, density, 7, t, false, 1f)
            for ((li, layout) in prep.plan.layouts.withIndex()) {
                checked++
                val area = layout.safeArea
                for ((k, p) in layout.words.withIndex()) {
                    val text = song.lyrics.lines[li].words[p.index].text
                    // the real text at its final size must not be wider than the box it was fitted in
                    val real = prep.words[li][k].advance
                    val outside = !area.contains(p.bounds())
                    val wider = real > p.width * 1.02f + 1f
                    if (!layout.fits || outside || wider) {
                        failures += "${song.name} | $sizeName | ${t.label} | line ${li + 1} '$text'" +
                            (if (outside) " outside" else "") + (if (wider) " measured ${real.toInt()} > ${p.width.toInt()}" else "")
                    }
                }
            }
        }
    }
    return FitResult(checked, failures, songs)
}

/** Static settled render of one line in all seven templates, phone sized. */
@Composable
private fun Preview(songs: List<Song>, density: Density, resolver: FontFamily.Resolver) {
    if (songs.isEmpty()) return
    var songIndex by remember { mutableIntStateOf(0) }
    var lineIndex by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    val song = songs[songIndex.coerceIn(0, songs.lastIndex)]
    val stage = with(density) {
        val (w, h) = if (landscape) 891f to 411f else 411f to 891f
        SizeF(w.dp.toPx(), h.dp.toPx())
    }
    val preps by produceState<List<PreparedStage>>(emptyList(), song, stage) {
        value = withContext(Dispatchers.Default) {
            val m = TextMeasurer(resolver, density, LayoutDirection.Ltr, 0)
            TemplateId.entries.map { PreparedStage.prepare(song.lyrics, stage, m, density, 7, it, false, 1f) }
        }
    }
    val renderer = remember { StageRenderer() }
    val colours = remember { StageColours(StagePalette.fallback(2), 0.85f) }
    val line = lineIndex.coerceIn(0, song.lyrics.lines.lastIndex)

    Spacer(Modifier.height(8.dp))
    Text(song.name + "  line ${line + 1}/${song.lyrics.lines.size}", color = Color.White, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { songIndex = (songIndex + 1) % songs.size; lineIndex = 0 }) { Text("Song") }
        TextButton(onClick = { lineIndex = (line - 1).coerceAtLeast(0) }) { Text("Prev") }
        TextButton(onClick = { lineIndex = (line + 1).coerceAtMost(song.lyrics.lines.lastIndex) }) { Text("Next") }
        TextButton(onClick = { landscape = !landscape }) { Text(if (landscape) "Portrait" else "Landscape") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        preps.forEach { prep ->
            val l = prep.plan.lines.getOrNull(line) ?: return@forEach
            // every word sung and settled, just before the exit starts
            val pos = (l.words.lastOrNull()?.endMs ?: l.startMs).coerceAtMost(l.endMs - 300)
            Canvas(
                Modifier
                    .weight(1f)
                    .aspectRatio(stage.width / stage.height)
                    .background(Color(0xFF15151E))
                    .border(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                val s = size.width / stage.width
                withTransform({ scale(s, s, Offset.Zero) }) {
                    with(renderer) { drawLine(prep, line, pos, colours, true, StageLayer.ALL) }
                    val a = prep.plan.layouts[line].safeArea
                    drawRect(Color(0x55FF375F), Offset(a.left, a.top), Size(a.width, a.height), style = Stroke(2f / s))
                }
            }
        }
    }
}
