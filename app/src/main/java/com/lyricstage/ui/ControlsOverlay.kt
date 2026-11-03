package com.lyricstage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricstage.R
import com.lyricstage.core.clock.PlaybackClock
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.media.NowPlaying
import com.lyricstage.settings.StageSettings
import kotlinx.coroutines.delay

/** Tap-to-show controls: artwork, title, seek bar, transport, template picker, sync nudge. */
@Composable
fun ControlsOverlay(
    nowPlaying: NowPlaying,
    clock: PlaybackClock,
    settings: StageSettings,
    offsetMs: Long,
    onInteract: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onTemplate: (TemplateId?) -> Unit,
    onNudge: (Long) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val position by produceState(clock.smoothedPositionMs()) {
        while (true) {
            value = clock.smoothedPositionMs()
            delay(200)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = nowPlaying.durationMs ?: 0L
    val art = remember(nowPlaying.art) { nowPlaying.art?.asImageBitmap() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (art != null) {
                    Image(
                        art,
                        contentDescription = stringResource(R.string.artwork),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(nowPlaying.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(nowPlaying.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.settings), color = Color.White) }
            }

            if (duration > 0) {
                Slider(
                    value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = {
                        dragging = it
                        onInteract()
                    },
                    onValueChangeFinished = {
                        dragging?.let { onSeek((it * duration).toLong()) }
                        dragging = null
                    },
                    enabled = nowPlaying.canSeek,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                )
                Row {
                    Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onPrevious(); onInteract() }) { TransportIcon(Transport.PREVIOUS, stringResource(R.string.previous)) }
                Spacer(Modifier.width(20.dp))
                FilledIconButton(onClick = { onPlayPause(); onInteract() }, modifier = Modifier.size(60.dp)) {
                    TransportIcon(if (nowPlaying.isPlaying) Transport.PAUSE else Transport.PLAY, stringResource(if (nowPlaying.isPlaying) R.string.pause else R.string.play))
                }
                Spacer(Modifier.width(20.dp))
                IconButton(onClick = { onNext(); onInteract() }) { TransportIcon(Transport.NEXT, stringResource(R.string.next)) }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    TemplateChip(stringResource(R.string.template_auto), settings.template == null) { onTemplate(null); onInteract() }
                }
                items(TemplateId.entries) { id ->
                    TemplateChip(id.label, settings.template == id) { onTemplate(id); onInteract() }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onNudge(-250); onInteract() }) { Text(stringResource(R.string.sync_earlier), color = Color.White) }
                Text(
                    stringResource(R.string.sync_label, offsetMs.toInt()),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                TextButton(onClick = { onNudge(250); onInteract() }) { Text(stringResource(R.string.sync_later), color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onImport) { Text(stringResource(R.string.import_lrc), color = Color.White) }
            }
            Text(stringResource(R.string.sync_hint), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun TemplateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
        ),
    )
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
