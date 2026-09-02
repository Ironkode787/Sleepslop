# Pre-rendered audio

Offline renders of Sleepslop sounds as plain audio files — for playing on
devices that can't run the app (e.g. an iPhone), where a normal audio file
loops all night with the screen locked.

## boxfan-20min.m4a

- **Sound:** Box fan, standard tuning (default level, no room EQ / brightness
  tilt / drift / space).
- **Length:** 20:00, 44.1 kHz stereo AAC (~24 MB), plays natively on iOS
  (Files app, any looping-audio app).
- **Seamless loop:** a 300 ms equal-power crossfade wraps the tail into the
  head, so repeat playback has no seam. The box fan is a steady texture, so a
  loop is perceptually identical to the live synth.

Regenerate with `render_boxfan.py`, which mirrors `BoxFan` in
`app/src/main/java/com/sleepslop/audio/Generators.kt` and the low-pass
`Biquad` in `Dsp.kt` exactly:

```
pip install numpy scipy imageio-ffmpeg   # imageio-ffmpeg bundles ffmpeg
python3 render_boxfan.py                  # writes boxfan-20min.wav
ffmpeg -i boxfan-20min.wav -c:a aac -b:a 160k -movflags +faststart boxfan-20min.m4a
```
