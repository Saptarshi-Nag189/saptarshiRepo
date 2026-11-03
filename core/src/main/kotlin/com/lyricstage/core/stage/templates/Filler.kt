package com.lyricstage.core.stage.templates

/** Short function words that rarely carry a line. */
object Filler {
    private val WORDS = setOf(
        "a", "an", "the", "to", "of", "in", "on", "at", "by", "for", "from", "with", "and", "or", "but", "so",
        "is", "am", "are", "was", "be", "i", "i'm", "me", "my", "we", "it", "it's", "oh", "ooh", "ah", "yeah",
        "your", "you're", "that", "this", "just", "as", "if",
        // romanised Hindi particles
        "ka", "ki", "ke", "ko", "se", "hai", "hain", "ne", "na", "bhi", "toh",
    )
    private val STRIP = Regex("[^\\p{L}']")

    fun isFiller(word: String): Boolean = STRIP.replace(word.lowercase(), "") in WORDS
}
