package com.lyricstage.stage

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricstage.core.clock.PlaybackClock
import com.lyricstage.core.lyrics.Lyrics
import com.lyricstage.core.palette.StagePalette
import com.lyricstage.core.stage.LineTiming
import com.lyricstage.core.stage.Ripple
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.TemplateId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The kinetic lyrics stage. One frame loop reads the clock once per frame; drawing happens
 * in two Canvas passes (settled words, and fast-moving words under a blur layer on API 31+).
 * Nothing recomposes per frame.
 */
@Composable
fun LyricStage(
    lyrics: Lyrics,
    clock: PlaybackClock,
    palette: StagePalette,
    playSeed: Int,
    lockedTemplate: TemplateId?,
    fontScale: Float,
    highlightIntensity: Float,
    reduceMotion: Boolean,
    isPlaying: Boolean,
    showFps: Boolean,
    onRipple: (Ripple) -> Unit,
    onVisibleTemplate: (TemplateId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    val prepared by produceState<PreparedStage?>(null, lyrics, size, playSeed, lockedTemplate, reduceMotion, fontScale, density) {
        if (size.width == 0 || size.height == 0 || lyrics.lines.isEmpty()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            // a private measurer: the shared one is not meant for background threads
            val measurer = TextMeasurer(resolver, density, LayoutDirection.Ltr, 0)
            PreparedStage.prepare(
                lyrics, SizeF(size.width.toFloat(), size.height.toFloat()), measurer, density,
                playSeed, lockedTemplate, reduceMotion, fontScale,
            )
        }
    }

    val frame = remember { StageFrame() }
    val renderer = remember { StageRenderer() }
    val colours = remember(palette, highlightIntensity) { StageColours(palette, highlightIntensity) }
    val ripple by rememberUpdatedState(onRipple)
    val visibleTemplate by rememberUpdatedState(onVisibleTemplate)
    val calm by rememberUpdatedState(reduceMotion)
    val prep = prepared

    LaunchedEffect(prep, clock) {
        frame.tracker.reset()
        var lastTemplate: TemplateId? = null
        while (isActive) {
            withFrameNanos { nanos ->
                val pos = clock.currentPositionMs()
                frame.positionMs = pos
                if (prep != null) {
                    val li = LineTiming.visibleLine(prep.plan.lyrics, pos)
                    frame.line = li
                    frame.blur.floatValue = renderer.motionBlur(prep, li, pos, calm)
                    frame.ripples.clear()
                    if (!calm) frame.tracker.update(prep.plan, pos, frame.ripples)
                    for (r in frame.ripples) ripple(r)
                    val t = if (li >= 0) prep.plan.templates[li] else null
                    if (t != null && t != lastTemplate) {
                        lastTemplate = t
                        visibleTemplate(t)
                    }
                } else {
                    frame.line = -1
                    frame.blur.floatValue = 0f
                }
                frame.countFrame(nanos)
                frame.tick.longValue = nanos
            }
        }
    }

    // the stage dims while paused and holds the current line
    val dim by animateFloatAsState(if (isPlaying) 1f else 0.55f, tween(400), label = "pause-dim")

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer { alpha = dim },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame.tick.longValue
            val p = prep ?: return@Canvas
            with(renderer) {
                if (frame.line < 0) drawGapDots(p, frame.positionMs, colours, reduceMotion)
                else drawLine(p, frame.line, frame.positionMs, colours, reduceMotion, StageLayer.SETTLED)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val b = frame.blur.floatValue
                        renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                    },
            ) {
                frame.tick.longValue
                val p = prep ?: return@Canvas
                with(renderer) { drawLine(p, frame.line, frame.positionMs, colours, reduceMotion, StageLayer.MOTION) }
            }
        }
        if (showFps) {
            val fps by frame.fps
            Text(
                "$fps fps",
                color = if (fps >= 55) Color(0xFF30D158) else Color(0xFFFF9F0A),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            )
        }
    }
}
