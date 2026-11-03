package com.lyricstage.core.text

import java.text.BreakIterator
import java.util.Locale

/** Splits a lyric line into the words the stage animates. */
object Tokenizer {
    fun tokenize(text: String, script: Script): List<String> =
        if (script == Script.JAPANESE) splitJapanese(text) else text.split(WS).filter { it.isNotEmpty() }

    private val WS = Regex("\\s+")

    private fun isKana(s: String) = s.all { it in '぀'..'ゟ' }
    private fun isPunct(s: String) = s.all { !Character.isLetterOrDigit(it) }

    /** Japanese has no spaces: group word-break segments into short phrases (2 to 7 chars). */
    private fun splitJapanese(text: String): List<String> {
        val chunks = ArrayList<String>()
        for (part in text.split(WS).filter { it.isNotEmpty() }) {
            if (Script.detect(part) != Script.JAPANESE) {
                chunks += part
                continue
            }
            val it = BreakIterator.getWordInstance(Locale.JAPANESE)
            it.setText(part)
            var start = it.first()
            var end = it.next()
            var current = ""
            while (end != BreakIterator.DONE) {
                val seg = part.substring(start, end)
                val attach = current.isNotEmpty() && (isPunct(seg) || (isKana(seg) && seg.length <= 2) || current.length < 2)
                if (attach && current.length + seg.length <= 7) {
                    current += seg
                } else {
                    if (current.isNotEmpty()) chunks += current
                    current = seg
                }
                start = end
                end = it.next()
            }
            if (current.isNotEmpty()) chunks += current
        }
        return chunks
    }
}
