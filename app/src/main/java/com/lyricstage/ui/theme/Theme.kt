package com.lyricstage.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.lyricstage.stage.StageFonts

private val Scheme = darkColorScheme(
    primary = Color(0xFFFF375F),
    onPrimary = Color.White,
    secondary = Color(0xFF64D2FF),
    background = Color(0xFF0B0B12),
    surface = Color(0xFF15151E),
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun LyricStageTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val inter = TextStyle(fontFamily = StageFonts.inter)
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography(
            headlineMedium = base.headlineMedium.merge(inter),
            titleLarge = base.titleLarge.merge(inter),
            titleMedium = base.titleMedium.merge(inter),
            bodyLarge = base.bodyLarge.merge(inter),
            bodyMedium = base.bodyMedium.merge(inter),
            labelLarge = base.labelLarge.merge(inter),
        ),
        content = content,
    )
}
