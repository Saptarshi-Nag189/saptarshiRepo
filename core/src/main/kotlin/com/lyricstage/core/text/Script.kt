package com.lyricstage.core.text

/** Writing systems the stage picks fonts and timing rules for. */
enum class Script {
    LATIN, DEVANAGARI, BENGALI, JAPANESE, KOREAN, OTHER;

    val isIndic: Boolean get() = this == DEVANAGARI || this == BENGALI
    val isCjk: Boolean get() = this == JAPANESE || this == KOREAN

    companion object {
        /** Dominant script of [text], by counting letters of each kind. */
        fun detect(text: String): Script {
            var latin = 0
            var deva = 0
            var beng = 0
            var ja = 0
            var ko = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                when (cp) {
                    in 0x0900..0x097F, in 0xA8E0..0xA8FF -> deva++
                    in 0x0980..0x09FF -> beng++
                    in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xFF66..0xFF9F -> ja++
                    in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> ko++
                    else -> if (Character.isLetter(cp) && cp < 0x0250) latin++
                }
                i += Character.charCount(cp)
            }
            val best = maxOf(latin, deva, beng, ja, ko)
            return when {
                best == 0 -> OTHER
                best == deva -> DEVANAGARI
                best == beng -> BENGALI
                best == ja -> JAPANESE
                best == ko -> KOREAN
                else -> LATIN
            }
        }
    }
}
