package com.lyricstage.palette

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import com.lyricstage.core.palette.PaletteBuilder
import com.lyricstage.core.palette.StagePalette
import com.lyricstage.core.palette.Swatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Base, vivid accents and a contrast-safe text colour from album artwork. */
object ArtworkPalette {
    suspend fun from(art: Bitmap?, seed: Int): StagePalette = withContext(Dispatchers.Default) {
        if (art == null || art.isRecycled) return@withContext StagePalette.fallback(seed)
        // hardware bitmaps can't be read pixel by pixel
        val readable = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val palette = Palette.from(readable).maximumColorCount(16).resizeBitmapArea(112 * 112).generate()
        PaletteBuilder.build(palette.swatches.map { Swatch(it.rgb, it.population) }, seed)
    }
}
