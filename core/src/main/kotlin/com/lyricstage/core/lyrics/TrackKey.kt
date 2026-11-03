package com.lyricstage.core.lyrics

import java.text.Normalizer

/** Stable key for cache rows and sync offsets: normalised `artist|title|durationSec`. */
object TrackKey {
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun of(artist: String, title: String, durationMs: Long?): String {
        val a = norm(TitleCleaner.cleanArtist(artist))
        val t = norm(TitleCleaner.cleanTitle(title))
        // round to whole seconds so players that report 213_412 and 213_000 agree
        val d = durationMs?.takeIf { it > 0 }?.let { (it + 500) / 1000 } ?: 0
        return "$a|$t|$d"
    }

    private fun norm(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        // strip accents from Latin only; Indic vowel signs are marks too and must stay
        val latinOnly = decomposed.all { it.code < 0x0300 || it.code in 0x0300..0x036F }
        val base = if (latinOnly) MARKS.replace(decomposed, "") else Normalizer.normalize(s.lowercase(), Normalizer.Form.NFC)
        return NON_WORD.replace(base, " ").trim()
    }
}
