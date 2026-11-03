package com.lyricstage.background

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lyricstage.core.palette.Colour
import com.lyricstage.core.palette.StagePalette
import com.lyricstage.settings.BackgroundMode
import kotlin.math.cos
import kotlin.math.sin

/** Shader time in seconds, shared by the background and the ripple emitter. */
class BackgroundClock {
    private val start = System.nanoTime()
    var speed = 1f

    fun seconds(): Float = ((System.nanoTime() - start) / 1e9).toFloat() * speed
}

@Composable
fun StageBackground(
    mode: BackgroundMode,
    palette: StagePalette,
    art: Bitmap?,
    ripples: RippleEmitter,
    clock: BackgroundClock,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    clock.speed = if (reduceMotion) 0.25f else 1f
    Box(modifier.fillMaxSize()) {
        when {
            mode == BackgroundMode.SOLID -> Canvas(Modifier.fillMaxSize()) { drawRect(Color(palette.base)) }
            mode == BackgroundMode.ARTWORK && art != null -> ArtworkBlur(art, palette)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> LiquidGlass(palette, ripples, clock)
            else -> GradientFallback(palette, clock)
        }
        // darken behind the text when the palette is bright
        val scrim = ContrastScrim.alphaFor(palette)
        if (scrim > 0f) Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = scrim)) }
    }
}

/** Liquid glass on API 33+, rendered at a third of the resolution and scaled up (it is soft). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LiquidGlass(palette: StagePalette, ripples: RippleEmitter, clock: BackgroundClock) {
    val shader = remember { RuntimeShader(LIQUID_GLASS_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    var full by remember { mutableStateOf(IntSize.Zero) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { time.floatValue = clock.seconds() }
    }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onSizeChanged { full = it }) {
        if (full.width == 0) return@Box
        val w = (full.width / SCALE).coerceAtLeast(2)
        val h = (full.height / SCALE).coerceAtLeast(2)
        val sizeDp = with(density) { DpSize(w.toDp(), h.toDp()) }
        Canvas(
            Modifier
                .requiredSize(sizeDp)
                .graphicsLayer {
                    // offscreen so the layer rasterises at low resolution, then scales up
                    compositingStrategy = CompositingStrategy.Offscreen
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = full.width / w.toFloat()
                    scaleY = full.height / h.toFloat()
                },
        ) {
            shader.setFloatUniform("uRes", size.width, size.height)
            shader.setFloatUniform("uTime", time.floatValue)
            shader.setColorUniforms(palette)
            shader.setFloatUniform("uRip", ripples.data)
            shader.setFloatUniform("uLineT", ripples.lineAt)
            drawRect(brush)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.setColorUniforms(p: StagePalette) {
    fun set(name: String, c: Int) = setFloatUniform(name, Colour.red(c) / 255f, Colour.green(c) / 255f, Colour.blue(c) / 255f)
    set("uC0", p.accents[0])
    set("uC1", p.accents.getOrElse(1) { p.accents[0] })
    set("uC2", p.accents.getOrElse(2) { p.accents[0] })
    set("uBase", p.base)
}

/** Below API 33: slowly drifting radial pools of the accent colours. */
@Composable
private fun GradientFallback(palette: StagePalette, clock: BackgroundClock) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { time.floatValue = clock.seconds() }
    }
    val base = Color(palette.base)
    val accents = palette.accents.map { Color(it) }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        drawRect(base)
        val r = size.maxDimension * 0.65f
        accents.forEachIndexed { i, c ->
            val phase = t * (0.05f + i * 0.013f) + i * 2.1f
            val centre = Offset(
                size.width * (0.5f + 0.35f * cos(phase)),
                size.height * (0.5f + 0.35f * sin(phase * 1.3f)),
            )
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.42f), Color.Transparent), centre, r), r, centre)
        }
    }
}

/** Album art, heavily blurred: downscaled then drawn large with filtering, plus a RenderEffect blur on API 31+. */
@Composable
private fun ArtworkBlur(art: Bitmap, palette: StagePalette) {
    val small = remember(art) {
        val readable = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        readable.scale(24, 24).asImageBitmap()
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = BlurEffect(60f, 60f, TileMode.Clamp)
                }
            },
    ) {
        drawRect(Color(palette.base))
        // cover the stage, keeping the square art centred
        val side = size.maxDimension
        drawImage(
            small,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(small.width, small.height),
            dstOffset = IntOffset(((size.width - side) / 2).toInt(), ((size.height - side) / 2).toInt()),
            dstSize = IntSize(side.toInt(), side.toInt()),
            alpha = 0.85f,
            filterQuality = FilterQuality.Low,
        )
    }
}

/** How much black to lay over the background so white text keeps at least 4.5:1. */
object ContrastScrim {
    /** Worst-case background luminance we budget for, before the scrim. */
    fun alphaFor(p: StagePalette): Float {
        val brightest = (p.accents + p.base).maxOf { Colour.luminance(it) }
        // the shader's tone mapping keeps pools at roughly 60 % of the accent luminance
        val bg = brightest * 0.6
        val target = 0.18 // white on 0.18 luminance is about 4.5:1
        if (bg <= target) return 0f
        // luminance scales with (1 - alpha) under a black overlay
        return (1.0 - target / bg).coerceIn(0.0, 0.55).toFloat()
    }
}

private const val SCALE = 3
