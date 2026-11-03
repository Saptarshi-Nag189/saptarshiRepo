package com.lyricstage.core.lyrics

/** A timed lyric row before word timing is worked out. */
data class RawLine(
    val startMs: Long,
    val text: String,
    /** Word start times from enhanced LRC `<mm:ss.xx>` tags, when present. */
    val wordStarts: List<TimedToken>? = null,
)

data class TimedToken(val startMs: Long, val text: String)

/** Parses LRC, including multiple timestamps per row, `[offset:]` and enhanced word tags. */
object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)>""")
    private val OFFSET_TAG = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val WS = Regex("\\s+")

    fun isSynced(raw: String): Boolean = raw.lineSequence().any { TIME_TAG.containsMatchIn(it) }

    fun hasWordTags(raw: String): Boolean = WORD_TAG.containsMatchIn(raw)

    fun parse(raw: String): List<RawLine> {
        // LRC offset: a positive value shifts lyrics earlier.
        var offset = 0L
        val out = ArrayList<RawLine>()
        for (row in raw.lineSequence()) {
            val offsetMatch = OFFSET_TAG.find(row)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLong()
                continue
            }
            val times = TIME_TAG.findAll(row).toList()
            if (times.isEmpty()) continue
            val body = row.replace(TIME_TAG, "").trim()

            var words: List<TimedToken>? = null
            if (WORD_TAG.containsMatchIn(body)) {
                val list = ArrayList<TimedToken>()
                val tags = WORD_TAG.findAll(body).toList()
                for ((i, tag) in tags.withIndex()) {
                    val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                    val text = body.substring(tag.range.last + 1, textEnd).trim()
                    if (text.isEmpty()) continue
                    val start = toMs(tag.groupValues[1], tag.groupValues[2]) - offset
                    // a tag may carry several space-separated words: they share its start
                    for (piece in text.split(WS).filter { it.isNotEmpty() }) list += TimedToken(start.coerceAtLeast(0), piece)
                }
                if (list.isNotEmpty()) words = list
            }
            val text = body.replace(WORD_TAG, " ").replace(WS, " ").trim()
            for (t in times) {
                val start = (toMs(t.groupValues[1], t.groupValues[2]) - offset).coerceAtLeast(0)
                out += RawLine(start, text, words)
            }
        }
        out.sortBy { it.startMs }
        return out
    }

    internal fun toMs(min: String, sec: String): Long {
        val s = sec.replace(':', '.')
        val dot = s.indexOf('.')
        val whole = if (dot < 0) s.toLong() else s.substring(0, dot).toLong()
        val frac = if (dot < 0) 0L else {
            // ".5" = 500 ms, ".05" = 50 ms, ".005" = 5 ms
            val f = s.substring(dot + 1).padEnd(3, '0').take(3)
            f.toLong()
        }
        return min.toLong() * 60_000 + whole * 1000 + frac
    }
}
