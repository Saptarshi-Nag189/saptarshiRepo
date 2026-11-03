# LyricStage

Personal Android app. Shows kinetic, word-synced lyrics for whatever is playing on the
phone (Spotify, YouTube Music, Apple Music, any player with a media session).
Not for the Play Store. Single user. No accounts, analytics or ads. The only network
access is LRCLIB.

## Build

Needs the Android SDK (platform 35) and JDK 17+.

    ./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew -PcoreOnly :core:test  # pure Kotlin core, no Android SDK needed

## Layout

```
core/   Pure Kotlin (JVM), unit tested
  lyrics/   LrcParser, WordTimingEstimator, LyricsBuilder, LrclibClient, TitleCleaner, TrackKey
  clock/    PlaybackClock (extrapolate, ease drift under 300 ms, snap on seeks, user offset)
  palette/  Colour maths, PaletteBuilder (dark base, vivid accents, contrast-safe text)
  stage/    Template geometry and motion, FitEngine, TemplateChooser, Highlights, StagePlan
  stage/templates/  Stack, Slide, Zoom, Letters, Scatter, Graph, Flow (one per file)
  text/     Script detection, grapheme clusters (Indic conjuncts kept whole), tokenizer
app/
  media/      NotificationListenerService, ActiveSessionTracker, NowPlaying
  lyrics/     LyricsRepository (cache, 60 s retry while playing, .lrc import), Room
  palette/    ArtworkPalette (AndroidX Palette into core PaletteBuilder)
  stage/      LyricStage composable, frame loop, StageRenderer, fonts, text measuring
  background/ Liquid glass AGSL shader, ripple emitter, gradient and artwork fallbacks
  settings/   SettingsStore
  ui/         Stage screen, controls overlay, settings, onboarding, debug screens
```

### Why a separate core module

Lyrics, timing, the clock and all template geometry are plain Kotlin, so they run as JVM
unit tests (including a FitCheck over every sample line, template, size and font scale).
Templates expose `layout()` (fit into the stage) and `pose()` (entrance transform for a
given time); the app's `StageRenderer` is the single `DrawScope` drawing path for all of
them. That splits the `LyricTemplate.draw` from the original brief into pure motion maths
(core) and one shared renderer (app).

## How the stage draws

- One `withFrameNanos` loop reads the clock once per frame and writes a frame object.
- Two `Canvas` passes read it: settled words, and fast-moving words on a layer with a
  `RenderEffect` blur (API 31+). Only draw and layer properties invalidate; nothing
  recomposes per frame.
- Every line is measured once at a 100 px reference size, fitted by `FitEngine`
  (shrink, then wrap, then balanced rows), then each word is laid out again at its final
  size. Settled text is drawn at scale exactly 1 with no blur.
- Springs are closed form (`Spring.value(ms)`), so any position, including seeks, draws
  correctly, and a settled spring returns exactly 1.

## Debug screens (Settings, Debug)

- Now playing and clock: live metadata; raw, smoothed and shown position; drift trace.
- Lyrics line list: fetched lines with the current one highlighted.
- FitCheck: every line of every cached song and bundled sample, in every template, at
  phone and landscape sizes with the real fonts. Lists clipped or overflowing words.

## Build phases

| Phase | Status |
|---|---|
| 1 Plumbing | Written. Not yet built on a device. |
| 2 Clock | Written; core clock unit tested. |
| 3 Lyrics | Written; parser, estimator, LRCLIB client unit tested (mock server). |
| 4 Stage v1 | Written. |
| 5 Templates, chooser, FitEngine, FitCheck | Written; JVM FitCheck: 0 failures. |
| 6 Background | Written. |
| 7 Controls and settings | Written. |
| 8 Polish | Reduce motion, empty states, gap dots written. Perf pass needs a device. |

The first build was done in an environment that could not reach Google's Maven
repository or the Android SDK, so `assembleDebug` has not run yet. The app sources were
type-checked against the Android API 35 framework jar and Compose 1.7 (desktop), with
small stubs for AndroidX libraries. Expect a short round of fixes on first real build.

## Credits

See `ref/CREDITS.md`. Design ideas ported from LyricalStudio (MIT). Fonts under OFL.
