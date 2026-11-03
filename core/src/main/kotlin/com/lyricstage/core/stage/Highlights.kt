package com.lyricstage.core.stage

import com.lyricstage.core.lyrics.LyricLine
import com.lyricstage.core.stage.templates.Filler

/**
 * Picks 1 to 3 words per line to colour with a vivid accent. Random per playback (the
 * play seed changes every time a track starts) but stable within one play: repeated
 * lines get the same highlights.
 */
object Highlights {
    fun pick(line: LyricLine, playSeed: Int): Set<Int> {
        val usable = line.words.indices.filter { !line.words[it].backing }
        if (usable.isEmpty()) return emptySet()
        val pool = usable.filter { !Filler.isFiller(line.words[it].text) }.ifEmpty { usable }.toMutableList()
        val r = Rng(playSeed xor Rng.hash(line.text) xor 0x51ED27)
        for (i in pool.indices.reversed()) {
            val j = (r.next() * (i + 1)).toInt().coerceAtMost(i)
            val tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp
        }
        val count = (line.words.size * r.range(0.15f, 0.3f)).toInt().coerceIn(1, minOf(3, pool.size))
        return pool.take(count).toSet()
    }

    /** Which of the three accents a line uses. */
    fun accentIndex(line: LyricLine, playSeed: Int): Int =
        Math.floorMod(Rng.hash(line.text) xor playSeed, 3)
}
