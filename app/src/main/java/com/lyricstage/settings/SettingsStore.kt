package com.lyricstage.settings

import android.content.Context
import android.provider.Settings as SystemSettings
import androidx.core.content.edit
import com.lyricstage.core.stage.TemplateId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackgroundMode { LIQUID, ARTWORK, SOLID }

data class StageSettings(
    /** Null means Auto. */
    val template: TemplateId? = null,
    val background: BackgroundMode = BackgroundMode.LIQUID,
    val fontScale: Float = 1f,
    /** 0 keeps highlights subtle, 1 is fully vivid with glow. */
    val highlightIntensity: Float = 0.85f,
    val reduceMotion: Boolean = false,
    val showFps: Boolean = false,
)

/** Small settings store on SharedPreferences, exposed as a StateFlow. */
class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<StageSettings> = _settings.asStateFlow()

    fun update(transform: (StageSettings) -> StageSettings) {
        val next = transform(_settings.value)
        prefs.edit {
            putString(K_TEMPLATE, next.template?.name)
            putString(K_BACKGROUND, next.background.name)
            putFloat(K_FONT, next.fontScale)
            putFloat(K_HIGHLIGHT, next.highlightIntensity)
            putBoolean(K_REDUCE, next.reduceMotion)
            putBoolean(K_FPS, next.showFps)
        }
        _settings.value = next
    }

    /** True when the system animator duration scale is 0 (animations off). */
    fun systemReducesMotion(): Boolean =
        SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun read() = StageSettings(
        template = TemplateId.fromName(prefs.getString(K_TEMPLATE, null)),
        background = prefs.getString(K_BACKGROUND, null)?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() } ?: BackgroundMode.LIQUID,
        fontScale = prefs.getFloat(K_FONT, 1f),
        highlightIntensity = prefs.getFloat(K_HIGHLIGHT, 0.85f),
        reduceMotion = prefs.getBoolean(K_REDUCE, false),
        showFps = prefs.getBoolean(K_FPS, false),
    )

    private companion object {
        const val K_TEMPLATE = "template"
        const val K_BACKGROUND = "background"
        const val K_FONT = "font_scale"
        const val K_HIGHLIGHT = "highlight"
        const val K_REDUCE = "reduce_motion"
        const val K_FPS = "show_fps"
    }
}
