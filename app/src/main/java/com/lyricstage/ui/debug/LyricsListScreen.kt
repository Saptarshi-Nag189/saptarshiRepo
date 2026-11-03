package com.lyricstage.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricstage.R
import com.lyricstage.lyrics.LyricsState
import com.lyricstage.stage.StageFonts
import com.lyricstage.ui.StageViewModel
import kotlinx.coroutines.delay

/** Phase 3 check: the fetched lyrics as a plain list, current line highlighted. */
@Composable
fun LyricsListScreen(vm: StageViewModel, onBack: () -> Unit) {
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val ready = state as? LyricsState.Ready
    val current by produceState(-1, ready) {
        while (true) {
            value = ready?.lyrics?.lastStartedLine(vm.clock.currentPositionMs()) ?: -1
            delay(100)
        }
    }
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem((current - 3).coerceAtLeast(0)) }

    DebugScaffold(stringResource(R.string.debug_lyrics), onBack) {
        val header = when (val s = state) {
            is LyricsState.Ready -> "${s.lyrics.kind} from ${s.source}, ${s.lyrics.lines.size} lines"
            else -> s.toString()
        }
        Text(header, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        val lines = ready?.lyrics?.lines.orEmpty()
        if (lines.isEmpty() && ready != null) Text(ready.lyrics.plainText, color = Color.White)
        LazyColumn(state = listState) {
            itemsIndexed(lines) { i, line ->
                val active = i == current
                Text(
                    line.text + (line.sub?.let { "\n$it" } ?: ""),
                    color = if (active) Color.Black else Color.White.copy(alpha = if (i < current) 0.5f else 0.85f),
                    fontFamily = StageFonts.forScript(line.script),
                    fontWeight = if (active) FontWeight.Black else FontWeight.Medium,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (active) Color.White else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}
