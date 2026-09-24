# Stereo Widener

A small Android app that plays an audio file you pick and widens its stereo
image in real time using mid-side processing, with a slider from mono (0%)
through original (100%) up to strongly widened (250%).

## How it works
- `AudioWidenerEngine.kt` decodes the file with `MediaExtractor` +
  `MediaCodec` (so it supports MP3, AAC/M4A, WAV, OGG, FLAC — whatever
  codecs your device ships), converts each stereo PCM frame to mid/side,
  scales the side (difference) channel by the width factor, converts back
  to left/right, and streams it out through `AudioTrack`.
- `PlaybackService.kt` is a **foreground service** that owns the engine.
  Playback keeps running — with a notification and lock-screen media
  controls — even after you leave the app, switch to another app, or turn
  the screen off. `MainActivity.kt` binds to it and just reflects/controls
  its state; it doesn't own playback itself.
- The UI is a dark, card-based Material 3 layout (`Theme.StereoWidener`,
  `themes.xml`) rather than plain stock widgets.

## Background playback
Once you tap Play, a persistent notification appears with a **Stop**
action and title showing the current track — this is what lets Android
know the app is doing real foreground work, so the system won't kill it
when you switch away. Tapping the notification reopens the app; tapping
Stop (in the notification, or the in-app Play/Stop button) fully stops
playback and removes the notification. There's no separate pause state —
Stop always ends the session cleanly, and Play always starts fresh from
the chosen file.

## Build & run
1. Open this folder in **Android Studio** (Giraffe/Koala or newer).
2. Let Gradle sync (it will download the Android Gradle Plugin + Kotlin
   plugin — needs internet).
3. Connect your phone (USB debugging on) or use an emulator, hit Run.
4. In the app: **Choose Audio File** → pick any song from your phone →
   **Play** → drag the slider to taste. On first launch it'll ask for
   microphone access (for the spectrum visualizer's FFT tap — nothing is
   recorded) and, on Android 13+, notification permission (needed for the
   background-playback notification to actually show).
5. Play the phone's audio out to your head unit the way you already do
   (Bluetooth, AUX, or USB) — the widened signal comes out of the phone's
   normal audio output, same as any other player.

## Important limitation — please read
This widens audio that **this app itself decodes and plays**. Android does
not allow a normal app to transparently intercept and modify audio that
*other* apps (Spotify, Android Auto's own audio path, the radio tuner,
etc.) send to the speakers — there's no supported hook for that without
root or being the device manufacturer. So:
- **Works great for:** local files you load into this app.
- **Does not affect:** audio played by other apps on the head unit.

If your real goal is "make everything play wider" system-wide, that
generally requires either (a) a custom ROM/DSP on the head unit itself, or
(b) accepting the built-in `Virtualizer` audio effect some head units
expose in their own settings (worth checking your unit's sound settings
menu for something like "3D Sound" or "Surround" first — it may already
have this).

## 48-band equalizer + spectrum visualizer
- `BiquadFilter.kt` / `EqualizerEngine.kt`: a 48-band graphic EQ built as a
  cascade of peaking (bell) IIR filters, log-spaced 20 Hz–20 kHz, each
  adjustable ±12 dB. Runs entirely on the CPU (no native code) on the same
  decoded PCM stream, right after the stereo-widening step.
- `EqualizerView.kt`: the on-screen 48-bar EQ. Drag anywhere to set that
  band's gain; **Reset EQ** flattens everything back to 0 dB.
- `SpectrumVisualizerView.kt`: a single view supporting **six live
  visualizer styles**, switchable from the dropdown above it:
  - **Bars** — classic bottom-anchored spectrum bars
  - **Mirrored Bars** — bars extending up/down from a center line
  - **Filled Curve** — a smooth-ish filled spectrum silhouette
  - **Waveform** — a real oscilloscope trace of the time-domain signal
  - **Circular** — spectrum radiating outward from a center ring
  - **Dot Matrix** — retro LED-style stacked dots per frequency bin
  All six are driven by the same live FFT + waveform data from Android's
  built-in `Visualizer` API attached to the track's own audio session —
  this needs the `RECORD_AUDIO` runtime permission (requested on first
  launch) even though nothing is recorded to disk; Android requires it for
  any FFT/waveform tap on an audio session. Deny it and playback + EQ still
  work, you just won't see the visualizer.

## Tuning
- `widthFactor = 1.0` → unchanged
- `widthFactor = 0.0` → collapses to mono
- `widthFactor > 2.0` → very wide, can sound phasey on some material —
  test with your own music before committing to a default.

## Notes
- The project doesn't include Gradle wrapper binaries (they're large and
  need a network fetch) — Android Studio will offer to generate them
  automatically the first time you open the project, or you can run
  `gradle wrapper` yourself if you have Gradle installed.
- On a 2GB-RAM head unit, running decode + the 48-band EQ + the live
  visualizer together is the heaviest combination this app does. If you
  hear crackle/stutter, try switching the visualizer off (there's no
  dedicated toggle yet, but picking any style still costs the same — the
  EQ is the bigger cost if you need to cut load, since 48 cascaded bands
  run on every sample).
