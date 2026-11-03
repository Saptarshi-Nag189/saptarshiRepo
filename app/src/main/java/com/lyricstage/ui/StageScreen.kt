package com.lyricstage.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricstage.R
import com.lyricstage.background.BackgroundClock
import com.lyricstage.background.RippleEmitter
import com.lyricstage.background.StageBackground
import com.lyricstage.core.lyrics.LyricsKind
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.lyrics.LyricsState
import com.lyricstage.stage.LyricStage
import kotlinx.coroutines.delay

@Composable
fun StageScreen(vm: StageViewModel, systemReduceMotion: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val palette by vm.palette.collectAsStateWithLifecycle()
    val seed by vm.playSeed.collectAsStateWithLifecycle()
    val offset by vm.offset.collectAsStateWithLifecycle()
    val reduceMotion = settings.reduceMotion || systemReduceMotion

    val bgClock = remember { BackgroundClock() }
    val ripples = remember { RippleEmitter() }
    LaunchedEffect(np?.trackKey) { ripples.clear() }

    var controlsVisible by remember { mutableStateOf(false) }
    var interactions by remember { mutableIntStateOf(0) }
    var visibleTemplate by remember { mutableStateOf<TemplateId?>(null) }
    LaunchedEffect(controlsVisible, interactions) {
        if (controlsVisible) {
            delay(CONTROLS_MS)
            controlsVisible = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.import(uri, context.contentResolver) { ok ->
                Toast.makeText(context, if (ok) R.string.import_done else R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickLrc = { importLauncher.launch(arrayOf("*/*")) }

    ImmersiveStage()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactions++
                    },
                    onLongPress = {
                        // lock whatever template is on screen now, or go back to Auto
                        val current = vm.settings.value.template
                        if (current != null) {
                            vm.setTemplate(null)
                            Toast.makeText(context, R.string.template_unlocked, Toast.LENGTH_SHORT).show()
                        } else {
                            visibleTemplate?.let {
                                vm.setTemplate(it)
                                Toast.makeText(context, context.getString(R.string.template_locked, it.label), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            },
    ) {
        StageBackground(settings.background, palette, np?.art, ripples, bgClock, reduceMotion)

        val current = np
        when {
            current == null || !current.hasTrack -> StageMessage(
                stringResource(R.string.state_no_session),
                stringResource(R.string.state_no_session_hint),
            )
            else -> when (val s = state) {
                LyricsState.Idle, LyricsState.Loading -> StageMessage(stringResource(R.string.state_loading), pulsing = true)
                LyricsState.NotFound -> StageMessage(
                    stringResource(R.string.state_no_lyrics),
                    stringResource(R.string.state_no_lyrics_hint),
                    action = stringResource(R.string.import_lrc),
                    onAction = pickLrc,
                )
                LyricsState.Unavailable -> StageMessage(
                    stringResource(R.string.state_unavailable),
                    stringResource(R.string.state_unavailable_hint),
                    action = stringResource(R.string.import_lrc),
                    onAction = pickLrc,
                )
                is LyricsState.Ready -> when {
                    s.lyrics.kind == LyricsKind.INSTRUMENTAL ->
                        StageMessage(stringResource(R.string.state_instrumental), pulsing = !reduceMotion)
                    s.lyrics.kind == LyricsKind.PLAIN ->
                        PlainLyricsView(s.lyrics.plainText, vm.clock, current.durationMs)
                    s.lyrics.lines.isEmpty() -> StageMessage(
                        stringResource(R.string.state_no_lyrics),
                        action = stringResource(R.string.import_lrc),
                        onAction = pickLrc,
                    )
                    else -> LyricStage(
                        lyrics = s.lyrics,
                        clock = vm.clock,
                        palette = palette,
                        playSeed = seed,
                        lockedTemplate = settings.template,
                        fontScale = settings.fontScale,
                        highlightIntensity = settings.highlightIntensity,
                        reduceMotion = reduceMotion,
                        isPlaying = current.isPlaying,
                        showFps = settings.showFps,
                        onRipple = { ripples.emit(it, bgClock.seconds()) },
                        onVisibleTemplate = { visibleTemplate = it },
                    )
                }
            }
        }

        AnimatedVisibility(controlsVisible && current != null, enter = fadeIn(), exit = fadeOut()) {
            if (current != null) {
                ControlsOverlay(
                    nowPlaying = current,
                    clock = vm.clock,
                    settings = settings,
                    offsetMs = offset,
                    onInteract = { interactions++ },
                    onPlayPause = { if (current.isPlaying) vm.sessions.pause() else vm.sessions.play() },
                    onPrevious = { vm.sessions.previous() },
                    onNext = { vm.sessions.next() },
                    onSeek = { vm.sessions.seekTo(it) },
                    onTemplate = { vm.setTemplate(it) },
                    onNudge = { vm.nudge(it) },
                    onImport = pickLrc,
                    onSettings = onOpenSettings,
                )
            }
        }
    }
}

private const val CONTROLS_MS = 3_000L
