package com.lyricstage.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricstage.R
import com.lyricstage.ui.StageViewModel

private class ClockSample(val raw: Long, val smoothed: Long, val shown: Long)

/** Phase 1 and 2 check: live metadata, and raw versus smoothed position with a drift trace. */
@Composable
fun NowPlayingDebugScreen(vm: StageViewModel, onBack: () -> Unit) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val offset by vm.offset.collectAsStateWithLifecycle()
    val history = remember { ArrayDeque<Long>() }
    val sample by produceState(ClockSample(0, 0, 0)) {
        while (true) withFrameMillis {
            val s = ClockSample(vm.clock.rawPositionMs(), vm.clock.smoothedPositionMs(), vm.clock.currentPositionMs())
            history.addLast(s.smoothed - s.raw)
            if (history.size > 240) history.removeFirst()
            value = s
        }
    }
    DebugScaffold(stringResource(R.string.debug_now_playing), onBack) {
        val p = np
        Column {
            Line("Title", p?.title ?: "none")
            Line("Artist", p?.artist ?: "")
            Line("Album", p?.album ?: "")
            Line("App", p?.packageName ?: "")
            Line("Playing", (p?.isPlaying ?: false).toString())
            Line("Duration", p?.durationMs?.let(::fmt) ?: "unknown")
            Line("Speed", (p?.speed ?: 1f).toString())
            Line("Track key", p?.trackKey ?: "")
            Spacer(Modifier.height(12.dp))
            Line(stringResource(R.string.debug_raw), fmt(sample.raw))
            Line(stringResource(R.string.debug_smoothed), fmt(sample.smoothed))
            Line("Smoothed minus raw", "${sample.smoothed - sample.raw} ms")
            Line(stringResource(R.string.debug_shown), fmt(sample.shown))
            Line("Offset", "$offset ms")
            Spacer(Modifier.height(12.dp))
            // trace of the eased correction: should decay smoothly to zero after each report
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                sample.raw // redraw every frame
                val mid = size.height / 2
                drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, mid), Offset(size.width, mid))
                val step = size.width / 240f
                var prev: Offset? = null
                history.forEachIndexed { i, d ->
                    val pt = Offset(i * step, mid - (d / 300f) * mid)
                    prev?.let { drawLine(Color(0xFF64D2FF), it, pt, 2f) }
                    prev = pt
                }
            }
            Text("Trace spans ±300 ms", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White)
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%03d".format(s / 60, s % 60, ms % 1000)
}
