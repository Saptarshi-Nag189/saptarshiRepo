package com.lyricstage.stage

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.palette.StagePalette
import com.lyricstage.core.stage.ENTRY_LEAD_MS
import com.lyricstage.core.stage.LineTiming
import com.lyricstage.core.stage.LyricTemplate
import com.lyricstage.core.stage.Pose
import com.lyricstage.core.stage.REF_PX
import com.lyricstage.core.stage.Templates
import com.lyricstage.core.stage.WordPlacement
import com.lyricstage.core.stage.easeOut
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Which words a pass draws: settled and slow ones, or the fast blurred ones. */
enum class StageLayer { SETTLED, MOTION, ALL }

class StageColours(palette: StagePalette, highlightIntensity: Float) {
    val text = Color(palette.text)
    val accents = palette.accents.map { lerp(text, Color(it), highlightIntensity.coerceIn(0f, 1f)) }
    val glow = highlightIntensity.coerceIn(0f, 1f)
}

/**
 * Draws one line of a [PreparedStage] for a playback position. Allocation free per frame:
 * poses and the skew matrix are reused. Word state comes from the clock, not from
 * recomposition, so seeking or scrubbing redraws correctly.
 */
class StageRenderer {
    private val pose = Pose()
    private val glyphPose = Pose()
    private val skew = Matrix()
    private val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Largest motion blur among the line's words right now (drives the motion layer). */
    fun motionBlur(prep: PreparedStage, li: Int, pos: Long, reduceMotion: Boolean): Float {
        if (reduceMotion || !blurSupported || li < 0) return 0f
        val layout = prep.plan.layouts[li]
        val line = prep.plan.lines[li]
        val template = Templates.of(layout.template)
        var b = 0f
        for (p in layout.words) {
            val since = sinceEnter(template, line.words[p.index].startMs, line, pos)
            if (since < 0) continue
            template.pose(p, since, layout.stage, pose)
            b = max(b, pose.blurPx)
        }
        return b
    }

    private fun sinceEnter(t: LyricTemplate, wordStart: Long, line: LyricLine, pos: Long): Long =
        if (t.showsUpcoming) pos - LineTiming.enterAtMs(line) else pos - (wordStart - ENTRY_LEAD_MS)

    fun DrawScope.drawLine(
        prep: PreparedStage,
        li: Int,
        pos: Long,
        colours: StageColours,
        reduceMotion: Boolean,
        layer: StageLayer,
    ) {
        if (li < 0 || li >= prep.plan.lines.size) return
        val plan = prep.plan
        val line = plan.lines[li]
        val layout = plan.layouts[li]
        val template = Templates.of(layout.template)
        val exit = easeOut(LineTiming.exitProgress(line, pos))
        val lineAlpha = 1f - exit
        if (lineAlpha <= 0f) return
        val highlights = plan.highlights[li]
        val accent = colours.accents[plan.accents[li] % colours.accents.size]

        if (layer != StageLayer.MOTION) {
            drawSegments(prep, li, pos, colours, lineAlpha)
            drawSubtitle(prep, li, pos, colours, lineAlpha)
        }

        for ((k, p) in layout.words.withIndex()) {
            val word = line.words[p.index]
            val since = sinceEnter(template, word.startMs, line, pos)
            if (since < 0) continue
            if (reduceMotion) {
                pose.reset()
                pose.alpha = (since / 220f).coerceIn(0f, 1f)
            } else {
                template.pose(p, since, layout.stage, pose)
            }
            val moving = blurSupported && pose.blurPx > 0.5f
            when (layer) {
                StageLayer.SETTLED -> if (moving) continue
                StageLayer.MOTION -> if (!moving) continue
                StageLayer.ALL -> Unit
            }
            val fill = LineTiming.fill(word, pos)
            val highlighted = p.index in highlights
            val colour = when {
                word.backing -> colours.text.copy(alpha = 0.62f)
                highlighted -> accent
                else -> colours.text
            }
            val alpha = pose.alpha * lineAlpha
            val exitDy = -exit * p.height * 0.25f
            val shadow = if (highlighted) {
                Shadow(accent.copy(alpha = 0.55f * colours.glow), Offset.Zero, p.fontPx * 0.28f)
            } else {
                Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, p.fontPx * 0.035f), p.fontPx * 0.18f)
            }

            withTransform({
                translate(p.x + pose.dx, p.y + pose.dy + exitDy)
                val pivot = Offset(p.width / 2f, p.height / 2f)
                val rot = p.rotationDeg + pose.rotationDeg
                if (rot != 0f) rotate(rot, pivot)
                if (pose.scaleX != 1f || pose.scaleY != 1f) scale(pose.scaleX, pose.scaleY, pivot)
                if (pose.skewX != 0f) {
                    skew.reset()
                    skew.values[Matrix.SkewX] = pose.skewX
                    translate(pivot.x, pivot.y)
                    transform(skew)
                    translate(-pivot.x, -pivot.y)
                }
            }) {
                val glyphs = prep.glyphs[li]?.get(k)
                if (glyphs != null && !reduceMotion) {
                    drawGlyphs(template, p, glyphs, prep.plan.measured[li].words[p.index].glyphOffsets, since, fill, colour, colours.text, alpha, shadow)
                } else {
                    drawWord(prep.words[li][k], p, fill, colour, colours.text, alpha, shadow)
                }
            }
        }
    }

    /** Dim base, then the sung part in full colour, swept left to right. */
    private fun DrawScope.drawWord(
        tl: TextLayoutResult, p: WordPlacement, fill: Float, colour: Color, dimColour: Color, alpha: Float, shadow: Shadow,
        clipLeft: Float = 0f,
    ) {
        val topLeft = Offset(0f, p.ascent - tl.firstBaseline)
        if (fill >= 1f) {
            drawText(tl, colour, topLeft, alpha, shadow)
            return
        }
        drawText(tl, dimColour, topLeft, alpha * DIM)
        if (fill > 0f) {
            val right = p.width * fill - clipLeft
            if (right > 0f) clipRect(left = -p.width, top = -p.height, right = right, bottom = p.height * 2f) {
                drawText(tl, colour, topLeft, alpha, shadow)
            }
        }
    }

    private fun DrawScope.drawGlyphs(
        template: LyricTemplate, p: WordPlacement, glyphs: List<TextLayoutResult>, offsetsAtRef: FloatArray,
        since: Long, fill: Float, colour: Color, dimColour: Color, alpha: Float, shadow: Shadow,
    ) {
        val k = p.fontPx / REF_PX
        for ((g, tl) in glyphs.withIndex()) {
            template.glyphPose(p, g, since, glyphPose)
            val a = alpha * glyphPose.alpha
            if (a <= 0f) continue
            val gx = (offsetsAtRef.getOrNull(g) ?: 0f) * k
            withTransform({
                translate(gx + glyphPose.dx, glyphPose.dy)
                if (glyphPose.scaleX != 1f || glyphPose.scaleY != 1f) {
                    scale(glyphPose.scaleX, glyphPose.scaleY, Offset(tl.advance / 2f, p.height / 2f))
                }
            }) {
                drawWord(tl, p, fill, colour, dimColour, a, shadow, clipLeft = gx)
            }
        }
    }

    /** Graph connectors draw in as the word they lead to starts. */
    private fun DrawScope.drawSegments(prep: PreparedStage, li: Int, pos: Long, colours: StageColours, lineAlpha: Float) {
        val layout = prep.plan.layouts[li]
        if (layout.segments.isEmpty()) return
        val line = prep.plan.lines[li]
        val width = max(2f, (layout.words.firstOrNull()?.fontPx ?: 40f) * 0.045f)
        for (s in layout.segments) {
            val start = line.words[s.revealWord].startMs - ENTRY_LEAD_MS
            val reveal = easeOut((pos - start) / 280f)
            if (reveal <= 0f) continue
            val a = Offset(s.x0, s.y0)
            val b = Offset(s.x0 + (s.x1 - s.x0) * reveal, s.y0 + (s.y1 - s.y0) * reveal)
            drawLine(colours.text.copy(alpha = 0.5f * lineAlpha), a, b, width, StrokeCap.Round)
            drawCircle(colours.text.copy(alpha = 0.8f * lineAlpha), width * 1.6f, a)
            if (reveal >= 1f) drawCircle(colours.text.copy(alpha = 0.8f * lineAlpha), width * 1.6f, b)
        }
    }

    private fun DrawScope.drawSubtitle(prep: PreparedStage, li: Int, pos: Long, colours: StageColours, lineAlpha: Float) {
        val tl = prep.subtitles[li] ?: return
        val area = prep.plan.layouts[li].subtitleArea ?: return
        val fade = ((pos - LineTiming.enterAtMs(prep.plan.lines[li])) / 300f).coerceIn(0f, 1f)
        drawText(
            tl,
            colours.text,
            Offset(area.centreX - tl.size.width / 2f, area.top),
            alpha = 0.72f * lineAlpha * fade,
            shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 8f),
        )
    }

    /**
     * Instrumental passages (and the intro): three slowly pulsing dots until the next line
     * is about to enter. Driven by playback position, so they freeze when paused.
     */
    fun DrawScope.drawGapDots(prep: PreparedStage, pos: Long, colours: StageColours, reduceMotion: Boolean) {
        val next = prep.plan.lines.firstOrNull { it.startMs > pos } ?: return
        if (next.startMs - pos < GAP_MIN_MS) return
        val r = size.minDimension * 0.012f
        val cy = size.height / 2f
        for (i in 0..2) {
            val phase = pos / 900.0 - i * 0.22
            val a = if (reduceMotion) 0.6f else (0.3 + 0.7 * (0.5 + 0.5 * sin(2 * PI * phase))).toFloat()
            drawCircle(colours.text.copy(alpha = a * 0.85f), r, Offset(size.width / 2f + (i - 1) * r * 3.6f, cy))
        }
    }

    companion object {
        /** Gaps shorter than this show nothing rather than dots. */
        const val GAP_MIN_MS = 2_500L

        /** Opacity of words not yet sung. */
        const val DIM = 0.32f
    }
}
