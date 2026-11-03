package com.lyricstage.core.lyrics

import com.lyricstage.core.text.Script
import com.lyricstage.core.text.Tokenizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Turns parsed LRC rows into stage-ready lines with word timings. */
object LyricsBuilder {
    private val NORM = Regex("[^\\p{L}\\p{N}]")

    fun fromLrc(raw: String, durationMs: Long?): Lyrics {
        val rows = LrcParser.parse(raw)
        val lines = build(rows, durationMs)
        val kind = if (rows.any { it.wordStarts != null }) LyricsKind.WORD_SYNCED else LyricsKind.LINE_SYNCED
        return Lyrics(kind, lines)
    }

    fun plain(text: String): Lyrics = Lyrics(LyricsKind.PLAIN, plainText = text.trim())

    private data class Row(val raw: RawLine, var sub: String? = null)

    /** Rows that share a timestamp are one lyric plus its romanisation or translation. */
    private fun mergeSameTime(input: List<RawLine>): List<Row> {
        val out = ArrayList<Row>()
        for (r in input) {
            val prev = out.lastOrNull()
            if (prev != null && r.text.isNotEmpty() && prev.raw.text.isNotEmpty() && abs(prev.raw.startMs - r.startMs) < 50) {
                prev.sub = prev.sub?.let { "$it / ${r.text}" } ?: r.text
            } else {
                out += Row(r)
            }
        }
        return out
    }

    private fun norm(t: String) = t.lowercase().replace(NORM, "")

    fun build(input: List<RawLine>, durationMs: Long?): List<LyricLine> {
        val rows = mergeSameTime(input)
        val counts = HashMap<String, Int>()
        for (r in rows) if (r.raw.text.isNotEmpty()) counts.merge(norm(r.raw.text), 1, Int::plus)

        val pace = learnPace(rows)
        val lines = ArrayList<LyricLine>()
        for (i in rows.indices) {
            val r = rows[i].raw
            if (r.text.isEmpty()) continue // empty timestamp: instrumental break marker
            val nextRaw = rows.getOrNull(i + 1)?.raw
            val next = nextRaw?.startMs ?: max(r.startMs + 4000, durationMs ?: (r.startMs + 8000))
            val gap = max(300L, next - r.startMs)
            val script = Script.detect(r.text)

            var words: List<LyricWord> = if (!r.wordStarts.isNullOrEmpty()) {
                val timed = r.wordStarts
                timed.mapIndexed { idx, w ->
                    val end = timed.getOrNull(idx + 1)?.startMs ?: min(next, w.startMs + 1200)
                    LyricWord(w.text, w.startMs, max(end, w.startMs + 1))
                }
            } else {
                val tokens = Tokenizer.tokenize(r.text, script)
                val estimated = WordTimingEstimator.lineWeight(r.text, script) * WordTimingEstimator.msPerWeight(script) * pace
                // never stretch a short line across a long instrumental gap
                val span = min(gap * 0.95, max(600.0, estimated * 1.1)).toLong()
                WordTimingEstimator.estimate(tokens, script, r.startMs, span)
            }
            words = markBackingAndEmphasis(words)

            val lastWordEnd = words.lastOrNull()?.endMs ?: (r.startMs + 1000)
            var nextSung = next
            for (k in i + 1 until rows.size) if (rows[k].raw.text.isNotEmpty()) { nextSung = rows[k].raw.startMs; break }
            // Keep the line up until the next one. Only a real break (marked, or a long
            // silence) or the last line ends it early.
            var end = nextSung
            val explicitBreak = nextRaw != null && nextRaw.text.isEmpty()
            if (nextRaw == null || nextSung - lastWordEnd > 14_000) {
                end = min(nextSung, max(lastWordEnd + 5000, r.startMs + 9000))
            } else if (explicitBreak && nextSung - next >= 10_000) {
                end = next
            }
            lines += LyricLine(
                index = lines.size,
                text = r.text,
                startMs = r.startMs,
                endMs = max(end, lastWordEnd),
                words = words,
                script = script,
                sub = rows[i].sub,
                repeats = counts[norm(r.text)] ?: 1,
            )
        }
        return lines
    }

    /**
     * Learn the song's singing pace from lines followed closely by another line: ballads
     * stretch syllables far longer than rap. Returns a multiplier on the default pace.
     */
    private fun learnPace(rows: List<Row>): Double {
        val paces = ArrayList<Double>()
        for (i in 0 until rows.size - 1) {
            val r = rows[i].raw
            val n = rows[i + 1].raw
            if (r.text.isEmpty() || n.text.isEmpty()) continue
            val script = Script.detect(r.text)
            val total = WordTimingEstimator.lineWeight(r.text, script)
            val gap = n.startMs - r.startMs
            if (total > 0 && gap in 501..11_999) paces += gap / total / WordTimingEstimator.msPerWeight(script)
        }
        if (paces.size < 4) return 1.0
        paces.sort()
        // lines usually fill about 85 % of their slot
        return (paces[paces.size / 2] * 0.85).coerceIn(0.6, 3.0)
    }

    private fun markBackingAndEmphasis(words: List<LyricWord>): List<LyricWord> {
        if (words.isEmpty()) return words
        val mean = words.sumOf { it.durationMs }.toDouble() / words.size
        var depth = 0
        return words.map { w ->
            if (w.text.contains('(')) depth++
            val backing = depth > 0
            if (w.text.contains(')')) depth = max(0, depth - 1)
            w.copy(backing = backing, emphasis = words.size >= 3 && w.durationMs > mean * 1.7)
        }
    }
}
