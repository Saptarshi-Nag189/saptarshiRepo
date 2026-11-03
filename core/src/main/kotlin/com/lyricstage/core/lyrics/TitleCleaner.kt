package com.lyricstage.core.lyrics

/** Strips decorations players add to titles that lyric databases do not use. */
object TitleCleaner {
    private val FEAT = Regex("""\s*[(\[](?:feat|ft|with|prod)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("""\s*[(\[][^)\]]*(?:remaster|version|official|lyric|audio|video|visuali[sz]er|mv|live|edit|explicit|clean)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val DASH_TAG = Regex("""\s+-\s+[^-]*(?:remaster|version|edit|mix|live|mono|stereo)[^-]*$""", RegexOption.IGNORE_CASE)
    private val INLINE_FEAT = Regex("""\s+(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\sx\s|\sfeat\.?\s|\sft\.?\s|\sand\s)\s*""", RegexOption.IGNORE_CASE)
    private val TOPIC = Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE)

    fun cleanTitle(title: String): String {
        var t = title
        t = FEAT.replace(t, "")
        t = TAGS.replace(t, "")
        t = DASH_TAG.replace(t, "")
        t = INLINE_FEAT.replace(t, "")
        return t.replace(Regex("\\s+"), " ").trim().ifEmpty { title.trim() }
    }

    fun cleanArtist(artist: String): String {
        val a = TOPIC.replace(artist, "")
        return a.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: a.trim()
    }
}
