package com.lyricstage.core.stage

import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.stage.TemplateId.FLOW
import com.lyricstage.core.stage.TemplateId.GRAPH
import com.lyricstage.core.stage.TemplateId.LETTERS
import com.lyricstage.core.stage.TemplateId.SCATTER
import com.lyricstage.core.stage.TemplateId.SLIDE
import com.lyricstage.core.stage.TemplateId.STACK
import com.lyricstage.core.stage.TemplateId.ZOOM

/**
 * Picks a template per line from its pace (words per second), its length and whether it is
 * a chorus (its text repeats). Never the same template twice in a row. Repeated lines get
 * the same template when they can, so a chorus looks familiar. Deterministic per play seed.
 */
object TemplateChooser {
    fun chooseAll(lines: List<LyricLine>, playSeed: Int, locked: TemplateId?, reduceMotion: Boolean): List<TemplateId> {
        if (locked != null) {
            val fixed = if (reduceMotion && locked == SCATTER) STACK else locked
            return List(lines.size) { fixed }
        }
        val out = ArrayList<TemplateId>(lines.size)
        var prev: TemplateId? = null
        for (line in lines) {
            val t = choose(line, playSeed, prev, reduceMotion)
            out += t
            prev = t
        }
        return out
    }

    fun choose(line: LyricLine, playSeed: Int, previous: TemplateId?, reduceMotion: Boolean): TemplateId {
        val n = line.words.size
        val seconds = ((line.words.lastOrNull()?.endMs ?: line.endMs) - line.startMs).coerceAtLeast(500) / 1000f
        val wps = n / seconds
        val energetic = wps > 2.5f
        val calm = wps < 1.4f
        val chorus = line.repeats >= 2
        val weights: List<Pair<TemplateId, Float>> = when {
            n <= 2 -> listOf(ZOOM to 3f, LETTERS to 2f, STACK to 1f, SLIDE to 0.5f)
            n > 11 || wps > 3.8f -> listOf(FLOW to 3f, SLIDE to 1.5f, STACK to 1f)
            chorus && n <= 7 -> if (energetic) listOf(ZOOM to 2f, SLIDE to 2f, LETTERS to 1f, STACK to 0.5f)
            else listOf(STACK to 2f, LETTERS to 1f, ZOOM to 1f, GRAPH to 0.5f)
            else -> listOf(
                STACK to (if (calm) 3f else 2f),
                SLIDE to (if (energetic) 3f else 2f),
                GRAPH to (if (n <= 8) (if (calm) 2.5f else 1.5f) else 0f),
                SCATTER to (if (energetic) 2f else 1f),
                ZOOM to (if (energetic) 2f else 1f),
                LETTERS to 1.5f,
                FLOW to (if (calm) 2f else 0.5f),
            )
        }
        val usable = weights.filter { (id, w) -> w > 0f && id != previous && !(reduceMotion && id == SCATTER) }
            .ifEmpty { listOf(STACK to 1f, ZOOM to 1f).filter { it.first != previous } }
        // seeded by text so repeated lines (the chorus) pick the same way
        val r = Rng(playSeed xor Rng.hash(line.text.lowercase()))
        val total = usable.sumOf { it.second.toDouble() }.toFloat()
        var x = r.next() * total
        for ((id, w) in usable) {
            x -= w
            if (x <= 0f) return id
        }
        return usable.first().first
    }
}
