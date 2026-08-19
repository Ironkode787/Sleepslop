# Sleepslop 🌙

A sleep-sound app for Android where **every sound is synthesized mathematically in real time** — no recordings, no loops, no seams. Mix as many sounds as you like, each with its own volume, set a sleep timer, and drift off.

## Sounds

| Sound | How it's made |
|---|---|
| 🌫️ White noise | Uniform random samples, decorrelated per channel |
| 🌸 Pink noise | Paul Kellet's −3 dB/octave economy filter |
| 🟤 Brown noise | Leaky integrator over white noise (−6 dB/octave) |
| 🌀 Deep tones | Binaural beat: 110 Hz left / 114 Hz right → 4 Hz delta-wave perception |
| 🌧️ Rain | Low-passed patter bed + high hiss + Poisson-spawned band-passed droplet bursts |
| 🌊 Ocean | Brown-noise surf amplitude-modulated by a randomly-timed wave envelope, spray hiss at crests |
| 🍃 Wind | White noise through a resonant bandpass whose center frequency and gain wander like gusts |
| 🦗 Forest night | Pink-noise foliage + three synthesized crickets (pulsed ~4 kHz sine syllables) |
| 🔥 Campfire | Brown rumble + random band-passed crackles and occasional low pops |
| 💨 Box fan | 53 Hz motor hum with harmonics, blade wobble, low-passed air noise |
| 🚂 Night train | Rolling brown drone with a rhythmic clickety-clack burst pattern |

## Features

- **Layer & mix** — run any combination of sounds simultaneously, each with its own volume slider (perceptual/squared volume curve, click-free gain ramping, soft-clip mixing).
- **Speaker tuning (room EQ)** — plays ~10 s of pink noise through the current output (phone or Bluetooth speaker), records it with the unprocessed microphone source, averages the spectrum over ~80 FFT windows, and compares 8 octave bands (63 Hz–8 kHz) against the ideal pink slope. The inverted deviation (capped at +6/−8 dB) becomes a chain of peaking biquads in the mixer. Steady-state measurement means Bluetooth latency is irrelevant. Toggle or recalibrate any time from the tune button.
- **Sleep timer** — 15 min to 8 h, with a gentle 45-second fade-out before stopping.
- **Background playback** — foreground service with a media notification, partial wake lock, and audio-focus handling (pauses when another app takes over the audio).
- **Persistent mix** — your selection and volumes are remembered across launches.
- **Animated night UI** — Jetpack Compose, drifting aurora glow, twinkling starfield, breathing accents while playing.

## Building

```bash
# Requires JDK 17+ and the Android SDK (compileSdk 35)
./gradlew assembleRelease
```

The release build is signed with the checked-in development keystore at `signing/sleepslop.jks` (passwords: `sleepslop`). Replace it with your own keystore before any store distribution — the bundled one is for convenience only and offers no security.

- **Min SDK**: 26 (Android 8.0) · **Target SDK**: 35
- Audio: `AudioTrack` streaming float PCM at 44.1 kHz stereo, 1024-frame buffers

## Architecture

- `audio/Dsp.kt` — RBJ biquad filters (LP/HP/BP/peaking), pink/brown noise filters
- `audio/Generators.kt` — every sound generator + the sound catalog
- `audio/Fft.kt` — radix-2 FFT for calibration analysis
- `audio/SpeakerTuner.kt` — mic-based speaker/room calibration + EQ chain
- `audio/AudioEngine.kt` — singleton mixer/render thread, EQ stage, timer, persistence
- `PlaybackService.kt` — foreground service, notification, wake lock
- `ui/` — Compose theme + single-screen UI
