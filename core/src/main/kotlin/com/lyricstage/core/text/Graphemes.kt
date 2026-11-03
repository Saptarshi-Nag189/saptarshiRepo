package com.lyricstage.core.text

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits text into the units a reader sees as one letter, so per-letter animation never
 * orphans a vowel sign or virama. Indic conjuncts (consonant + virama + consonant) stay
 * together as one akshara.
 */
object Graphemes {
    private val VIRAMAS = setOf('्', '্', '੍', '્', '୍', '்', '్', '್', '്')
    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'

    fun split(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(word)
        val clusters = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            clusters += word.substring(start, end)
            start = end
            end = it.next()
        }
        val out = ArrayList<String>(clusters.size)
        for (c in clusters) {
            val prev = out.lastOrNull()
            val joinsPrev = prev != null && (prev.last() in VIRAMAS || prev.last() == ZWJ || c.first() == ZWJ || c.first() == ZWNJ)
            val isMark = Character.getType(c.codePointAt(0)).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
            }
            if (prev != null && (joinsPrev || isMark)) out[out.lastIndex] = prev + c else out += c
        }
        return out
    }

    fun count(word: String): Int = split(word).size
}
