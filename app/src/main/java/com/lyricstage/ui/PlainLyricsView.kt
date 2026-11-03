package com.lyricstage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricstage.R
import com.lyricstage.core.clock.PlaybackClock
import com.lyricstage.core.text.Script
import com.lyricstage.stage.StageFonts

/**
 * Unsynced lyrics: the whole text scrolls slowly in step with the song's progress.
 * Clearly marked so it is never mistaken for synced lyrics.
 */
@Composable
fun PlainLyricsView(text: String, clock: PlaybackClock, durationMs: Long?) {
    val scroll = rememberScrollState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    LaunchedEffect(text, durationMs) {
        val duration = durationMs?.takeIf { it > 0 } ?: return@LaunchedEffect
        while (true) {
            withFrameMillis {
                // skip a short intro and outro, then track progress through the song
                val progress = ((clock.currentPositionMs() - duration * 0.06f) / (duration * 0.86f)).coerceIn(0f, 1f)
                val target = (scroll.maxValue * progress).toInt()
                if (!scroll.isScrollInProgress) {
                    val eased = scroll.value + ((target - scroll.value) * 0.08f).toInt()
                    if (eased != scroll.value) scroll.dispatchRawDelta((eased - scroll.value).toFloat())
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(screenHeight * 0.4f))
            Text(
                text,
                color = Color.White,
                fontFamily = StageFonts.forScript(Script.detect(text)),
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                lineHeight = 36.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(screenHeight * 0.5f))
        }
        Text(
            stringResource(R.string.plain_badge),
            color = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
