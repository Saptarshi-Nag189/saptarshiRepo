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
| 1 Plumbing | Builds. Needs a device run. |
| 2 Clock | Builds; clock unit tested. |
| 3 Lyrics | Builds; parser, estimator, LRCLIB client unit tested (mock server). |
| 4 Stage v1 | Builds. |
| 5 Templates, chooser, FitEngine, FitCheck | Builds; JVM FitCheck: 0 failures. |
| 6 Background | Builds. |
| 7 Controls and settings | Builds. |
| 8 Polish | Reduce motion, empty states, gap dots in. Perf pass needs a device. |

## CI

`.github/workflows/android.yml` runs on every push: core tests, `assembleDebug`, and
lint (errors fail the build). The debug APK is attached to each run as the
`lyricstage-debug-apk` artifact: open the run on GitHub, scroll to Artifacts, download,
unzip, install.

## Releases

Bump `VERSION` (and add a section to `CHANGELOG.md`), push, and
`.github/workflows/lyricstage-release.yml` builds a release APK and publishes it as a GitHub
Release tagged `lyricstage-vX.Y.Z`, with `SHA256SUMS.txt` and the changelog as notes.

Signing uses these repository secrets when present: `LYRICSTAGE_KEYSTORE_BASE64`,
`LYRICSTAGE_KEYSTORE_PASSWORD`, `LYRICSTAGE_KEY_ALIAS`, `LYRICSTAGE_KEY_PASSWORD`.
Without them it signs with a throwaway key, so each release must be installed fresh
(uninstall the previous one first).

## Credits

See `ref/CREDITS.md`. Design ideas ported from LyricalStudio (MIT). Fonts under OFL.
