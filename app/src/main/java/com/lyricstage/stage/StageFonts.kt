package com.lyricstage.stage

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import com.lyricstage.R
import com.lyricstage.core.text.Script

/** Bundled variable fonts, one family per script. Weight is set through the wght axis. */
object StageFonts {
    private fun family(res: Int) = FontFamily(
        Font(res, FontWeight.Black),
        Font(res, FontWeight.SemiBold),
        Font(res, FontWeight.Medium),
    )

    val inter = family(R.font.inter)
    private val devanagari = family(R.font.noto_sans_devanagari)
    private val bengali = family(R.font.noto_sans_bengali)
    private val japanese = family(R.font.noto_sans_jp)

    fun forScript(script: Script): FontFamily = when (script) {
        Script.DEVANAGARI -> devanagari
        Script.BENGALI -> bengali
        Script.JAPANESE -> japanese
        Script.KOREAN -> FontFamily.SansSerif
        else -> inter
    }

    /** Stage word style at an exact pixel size. */
    fun word(script: Script, fontPx: Float, density: Density): TextStyle = TextStyle(
        fontFamily = forScript(script),
        fontWeight = FontWeight.Black,
        fontSize = with(density) { fontPx.toSp() },
        // tight tracking suits heavy Latin type; other scripts keep shaping untouched
        letterSpacing = if (script == Script.LATIN || script == Script.OTHER) (-0.02).em else 0.em,
    )

    fun subtitle(script: Script, fontPx: Float, density: Density): TextStyle = TextStyle(
        fontFamily = forScript(script),
        fontWeight = FontWeight.SemiBold,
        fontSize = with(density) { fontPx.toSp() },
    )
}
