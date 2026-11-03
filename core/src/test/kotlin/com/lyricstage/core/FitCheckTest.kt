package com.lyricstage.core

import com.lyricstage.core.lyrics.LyricsBuilder
import com.lyricstage.core.stage.FitEngine
import com.lyricstage.core.stage.MeasuredLine
import com.lyricstage.core.stage.SizeF
import com.lyricstage.core.stage.Templates
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM version of the FitCheck debug screen: every line, every template, phone and landscape. */
class FitCheckTest {
    private val sizes = listOf(
        "phone" to SizeF(1080f, 2340f),
        "landscape" to SizeF(2340f, 1080f),
        "small" to SizeF(720f, 1280f),
        "small-landscape" to SizeF(1280f, 720f),
    )

    @Test fun everyLineFitsEverywhere() {
        val failures = ArrayList<String>()
        var checked = 0
        var tiny = 0
        for (name in Samples.names) {
            val lyrics = LyricsBuilder.fromLrc(Samples.read(name), 120_000)
            for (line in lyrics.lines) {
                val measured = MeasuredLine.measure(line, Samples.measure)
                for (t in Templates.all) for ((sizeName, size) in sizes) for (seed in listOf(1, 99, 12345)) {
                    val layout = t.layout(measured, size, seed)
                    checked++
                    val area = layout.safeArea
                    val outside = layout.words.filter { !area.contains(it.bounds()) }
                    if (!layout.fits || outside.isNotEmpty()) {
                        failures += "$name/${t.id}/$sizeName/$seed: '${line.text}' words ${outside.map { it.index }}"
                    }
                    if (layout.words.any { it.fontPx < FitEngine.minFont(size) * 0.6f }) tiny++
                }
            }
        }
        println("FitCheck: $checked layouts, ${failures.size} failures, $tiny below 60 % of min font")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
