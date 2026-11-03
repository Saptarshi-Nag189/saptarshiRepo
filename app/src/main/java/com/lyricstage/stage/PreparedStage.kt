package com.lyricstage.stage

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.lyricstage.core.lyrics.Lyrics
import com.lyricstage.core.stage.REF_PX
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.StagePlan
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.core.stage.Templates
import com.lyricstage.core.text.Script

/**
 * A [StagePlan] plus every text layout it draws, measured once at final sizes so each word
 * renders crisp at scale 1 once settled. Built on lyrics load and on stage size change.
 */
class PreparedStage(
    val plan: StagePlan,
    /** Per line, per placement (same order as `layout.words`). */
    val words: List<List<TextLayoutResult>>,
    /** Per line, per placement, per glyph; only for per-glyph templates. */
    val glyphs: List<List<List<TextLayoutResult>>?>,
    val subtitles: List<TextLayoutResult?>,
) {
    companion object {
        fun prepare(
            lyrics: Lyrics,
            stage: SizeF,
            measurer: TextMeasurer,
            density: Density,
            playSeed: Int,
            locked: TemplateId?,
            reduceMotion: Boolean,
            fontScale: Float,
        ): PreparedStage {
            val plan = StagePlan.build(lyrics, ComposeTextMeasure(measurer, density), stage, playSeed, locked, reduceMotion, fontScale)
            val words = ArrayList<List<TextLayoutResult>>(plan.lines.size)
            val glyphs = ArrayList<List<List<TextLayoutResult>>?>(plan.lines.size)
            val subs = ArrayList<TextLayoutResult?>(plan.lines.size)
            for ((li, layout) in plan.layouts.withIndex()) {
                val line = plan.lines[li]
                words += layout.words.map { p -> measurer.word(line.words[p.index].text, line.script, p.fontPx, density) }
                glyphs += if (Templates.of(layout.template).perGlyph) {
                    layout.words.map { p ->
                        plan.measured[li].words[p.index].glyphs.map { g -> measurer.word(g, line.script, p.fontPx, density) }
                    }
                } else null
                val area = layout.subtitleArea
                val sub = line.sub
                subs += if (area != null && !sub.isNullOrBlank()) {
                    val px = (area.height * 0.34f).coerceAtMost(REF_PX * 0.6f)
                    measurer.measure(
                        text = sub,
                        style = StageFonts.subtitle(Script.detect(sub), px, density).copy(textAlign = TextAlign.Center),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        constraints = Constraints(maxWidth = area.width.toInt().coerceAtLeast(1)),
                        density = density,
                    )
                } else null
            }
            return PreparedStage(plan, words, glyphs, subs)
        }
    }
}
