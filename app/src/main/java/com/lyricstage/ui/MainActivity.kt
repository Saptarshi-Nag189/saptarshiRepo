package com.lyricstage.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyricstage.LyricStageApp
import com.lyricstage.media.NotificationAccess
import com.lyricstage.ui.debug.FitCheckScreen
import com.lyricstage.ui.debug.LyricsListScreen
import com.lyricstage.ui.debug.NowPlayingDebugScreen
import com.lyricstage.ui.theme.LyricStageTheme

enum class Screen { STAGE, SETTINGS, DEBUG_NOW_PLAYING, DEBUG_LYRICS, FIT_CHECK }

class MainActivity : ComponentActivity() {
    private val container get() = (application as LyricStageApp).container
    private val accessGranted = mutableStateOf(false)
    private val systemReduceMotion = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LyricStageTheme {
                val vm: StageViewModel = viewModel { StageViewModel(container) }
                App(vm, accessGranted.value, systemReduceMotion.value)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshAccess()
    }

    override fun onResume() {
        super.onResume()
        // the user may have just granted access in system settings
        refreshAccess()
        systemReduceMotion.value = container.settings.systemReducesMotion()
    }

    override fun onStop() {
        super.onStop()
        container.sessions.stop()
    }

    private fun refreshAccess() {
        val granted = NotificationAccess.isGranted(this)
        accessGranted.value = granted
        if (granted) container.sessions.start()
    }
}

@Composable
private fun App(vm: StageViewModel, accessGranted: Boolean, systemReduceMotion: Boolean) {
    if (!accessGranted) {
        OnboardingScreen()
        return
    }
    var screen by rememberSaveable { mutableStateOf(Screen.STAGE) }
    BackHandler(enabled = screen != Screen.STAGE) {
        screen = if (screen == Screen.SETTINGS) Screen.STAGE else Screen.SETTINGS
    }
    when (screen) {
        Screen.STAGE -> StageScreen(vm, systemReduceMotion, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> SettingsScreen(vm, systemReduceMotion, onBack = { screen = Screen.STAGE }, onOpen = { screen = it })
        Screen.DEBUG_NOW_PLAYING -> NowPlayingDebugScreen(vm, onBack = { screen = Screen.SETTINGS })
        Screen.DEBUG_LYRICS -> LyricsListScreen(vm, onBack = { screen = Screen.SETTINGS })
        Screen.FIT_CHECK -> FitCheckScreen(vm, onBack = { screen = Screen.SETTINGS })
    }
}
