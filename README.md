# Sleepslop 🌙

A sleep-sound app for Android where **every sound is synthesized mathematically in real time** — no recordings, no loops, no seams. Mix as many sounds as you like, each with its own volume, set a sleep timer, and drift off.

## Sounds

| Sound | How it's made |
|---|---|
| 🌫️ White noise | Uniform random samples, decorrelated per channel |
| 🌸 Pink noise | Paul Kellet's −3 dB/octave economy filter |
| 🟤 Brown noise | Leaky integrator over white noise (−6 dB/octave) |
| 🌀 Deep tones | Binaural beat: 110 Hz left / 114 Hz right → 4 Hz delta-wave perception |
| 🌧️ Rain | Five-layer rainfall: distant wash, dense grain patter, close splats, canopy hiss, gutter drips — one coherent gust LFO drives them all. Params: intensity, surface, drips |
| 🌊 Ocean | Three overlapping wave voices (build → soft break → long wash with foam grains) over a constant sea bed; crest dynamics capped for sleep. Params: swell, period, foam |
| 🍃 Wind | Gust engine (weather/gust/flutter timescales) driving lagged layers: sub-100 Hz buffet, three parallel wandering whoosh bands, fast foliage hiss, rare gust-peak whistle. Params: strength, gustiness, foliage |
| 🌲 Forest night | Distant cricket wash + three individual near crickets (with dropouts) + raspy katydids + breeze rustles + spring peepers calling in bouts over a blurred wetland chorus + a far two-hoot owl + a granular brook (grain-modulated sparkle and gurgle bands + rising Minnaert bubbles) + rare one-off events (twig snap, animal rustle, night-bird note). Params: life, breeze, night voices, stream |
| 🔥 Campfire | Breathing flame roar (wandering 3–12 Hz flicker), three crackle populations arriving in flurries, log-settle whumps, sizzle trains, ember shimmer. Params: size, crackle |
| 💨 Box fan | 53 Hz motor hum with harmonics, blade wobble, low-passed air noise |
| 🌪️ Simulated fan | Physical fan model: blade-pass hum harmonics, the box fan's warm 53 Hz motor growl (rpm-tracking, with blade wobble), 100 Hz mains buzz, per-blade-modulated turbulence, tip-vortex whoosh. Params: speed (rpm), blades, motor hum, motor buzz, size, distance, oscillation |
| 🚂 Night train | Geometry simulation: 20 axles at real car/bogie offsets crossing real jointed-rail positions produce the authentic clack-clack…clack-clack lilt; rolling drone with coach sway, wind rush, distant horns, bridge passages. Params: speed, distance (inside ↔ across the valley), jointed rail |

## Elements

A second tab of modular ambient components, each with its own occurrence/character parameters plus volume — layer them over any mix:

| Element | Parameters |
|---|---|
| 🦗 Crickets | chirp rate, swarm size (1–6 voices), pitch |
| 🐸 Frogs | croak rate, pitch (pulsed two-harmonic croaks with downward glide) |
| 🦉 Owl | hoot rate, pitch ("hoo-hoo-hoooo" with vibrato on the long note) |
| ⛈️ Distant thunder | storm activity, distance — each strike is 4–8 overlapping sub-peals rolling 10–25 s across the sky with resurgences, a felt whump up close, and a breathing storm bed between strikes |
| 🎐 Wind chimes | breeze (gust-clustered strikes), shimmer (pentatonic two-partial tones) |
| 💓 Heartbeat | tempo, softness (lub-dub with downward pitch glide; womb-like when soft) |
| 🐈 Cat purr | purr rate, breathiness (pulse train with inhale/exhale alternation) |
| 🕰️ Clock tick | tempo, mellowness (impulse-excited wooden resonances) |
| ☕ Café murmur | crowd, clatter (formant-filtered babble voices, soft clinks) |
| 🚢 Foghorn | frequency, distance (two-tone blasts across still water) |
| 🐦 Dawn chorus | activity, variety — three species archetypes (whistler/chipper/triller) singing seeded phrase repertoires with curved pitch contours, envelope-tracked harmonics, breath noise and a built-in outdoor echo; used by wake-up |
| 💧 Water drops | rate, tone, echo, trickle — stone plinks + Minnaert pool bloops through a feedback-delay cave, over a trickle bed |

## Features

- **Layer & mix** — run any combination of sounds simultaneously, each with its own volume slider (perceptual/squared volume curve, click-free gain ramping, soft-clip mixing).
- **Speaker tuning (room EQ)** — plays ~10 s of pink noise through the current output (phone or Bluetooth speaker), records it with the unprocessed microphone source, averages the spectrum over ~80 FFT windows, and compares 8 octave bands (63 Hz–8 kHz) against the ideal pink slope. The inverted deviation (capped at +6/−8 dB) becomes a chain of peaking biquads in the mixer. Steady-state measurement means Bluetooth latency is irrelevant. Toggle or recalibrate any time from the tune button.
- **Presets** — save the current mix + all parameters under a name; six curated built-ins (Rainy night, Seaside, Deep focus, Summer meadow, Night train home, Cozy cabin).
- **Drift** — slow multi-timescale evolution of every layer's level (2–12 min cycles plus occasional quiet spells), so the mix breathes like a real place.
- **Space** — master stereo width (bass-safe mid/side) and a small dark room (allpass + cross-coupled feedback delays, RT60 ≈ 0.6 s).
- **Per-sound brightness** — a ±6 dB spectral tilt on every active sound, from its Tune sheet.
- **Deep-tone programs** — adjustable binaural beat (1–12 Hz) and carrier, plus a "descend" mode that glides to 2.5 Hz over 20 minutes.
- **Sleep timer** — 15 min to 8 h, 3 s fade-in on start and a perceptually smooth quadratic 60 s fade-out.
- **Smart sleep timer** — with the phone on the mattress, the accelerometer watches for stillness; 20 quiet minutes triggers the fade-out.
- **Wake-up** — arm a time and the night mix cross-fades into a synthesized dawn chorus over the final 12 minutes.
- **Bedside clock mode** — OLED-black dimmed clock (moon or night-vision ember tint), immersive, keeps the screen on at 5% brightness.
- **Lock-screen media controls** — MediaSession: play/pause from the lock screen, headset buttons, and watches; pauses when headphones disconnect.
- **Background playback** — foreground service with a media-style notification, partial wake lock, audio-focus handling, and an optional battery-optimization exemption prompt for aggressive OEMs.
- **Persistent everything** — mix, volumes, parameters, tilt, drift, space, and presets survive restarts.
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

- `audio/Dsp.kt` — RBJ biquad filters (LP/HP/BP/peaking), one-pole LP, pink/brown noise filters
- `audio/Generators.kt` — main sound generators + the catalog (sounds, elements, parameters)
- `audio/Elements.kt`, `audio/Elements2.kt` — parameterized ambient elements
- `audio/Water.kt` — layered rain / ocean / water-drop models
- `audio/Nature.kt` — campfire / wind / forest-night models
- `audio/Thunder.kt` — rolling multi-peal distant-thunder model
- `audio/Birdsong.kt` — three-species dawn-chorus model
- `audio/Train.kt` — bogie-geometry night-train model
- `audio/SimulatedFan.kt` — physically-inspired fan model
- `audio/Space.kt` — stereo width + room diffusion, per-sound tilt filter
- `audio/Drift.kt` — slow mix-evolution engine
- `audio/SmartTimer.kt`, `audio/WakeRoutine.kt` — stillness-triggered fade-out, dawn-chorus alarm
- `data/PresetStore.kt` — preset persistence
- `audio/Fft.kt` — radix-2 FFT for calibration analysis
- `audio/SpeakerTuner.kt` — mic-based speaker/room calibration + EQ chain
- `audio/AudioEngine.kt` — singleton mixer/render thread, EQ stage, timer, persistence
- `PlaybackService.kt` — foreground service, notification, wake lock
- `ui/` — Compose theme + single-screen UI
