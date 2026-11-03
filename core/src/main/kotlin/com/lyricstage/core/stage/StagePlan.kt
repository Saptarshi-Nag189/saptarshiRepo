package com.lyricstage.core.stage

import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.lyrics.Lyrics

/** Everything precomputed for drawing a track at one stage size. */
class StagePlan(
    val lyrics: Lyrics,
    val stage: SizeF,
    val playSeed: Int,
    val templates: List<TemplateId>,
    val highlights: List<Set<Int>>,
    val accents: List<Int>,
    val measured: List<MeasuredLine>,
    val layouts: List<TemplateLayout>,
) {
    val lines: List<LyricLine> get() = lyrics.lines

    companion object {
        /**
         * Measure every line once, choose templates and fit each line. Run off the main
         * thread on lyrics load and on stage size change.
         */
        fun build(
            lyrics: Lyrics,
            measure: TextMeasure,
            stage: SizeF,
            playSeed: Int,
            locked: TemplateId?,
            reduceMotion: Boolean,
        ): StagePlan {
            val lines = lyrics.lines
            val measured = lines.map { MeasuredLine.measure(it, measure) }
            val templates = TemplateChooser.chooseAll(lines, playSeed, locked, reduceMotion)
            val layouts = lines.indices.map { i ->
                val seed = playSeed xor Rng.hash(lines[i].text)
                Templates.of(templates[i]).layout(measured[i], stage, seed)
            }
            return StagePlan(
                lyrics, stage, playSeed, templates,
                lines.map { Highlights.pick(it, playSeed) },
                lines.map { Highlights.accentIndex(it, playSeed) },
                measured, layouts,
            )
        }
    }
}
