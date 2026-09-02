#!/usr/bin/env python3
"""
Faithful offline render of Sleepslop's Box Fan sound (standard tuning).

Mirrors app/src/main/java/com/sleepslop/audio/Generators.kt (class BoxFan)
and the RBJ low-pass Biquad in Dsp.kt exactly:

  hum   = (sin(p)*0.5 + sin(2p)*0.20 + sin(3p)*0.08) * 0.30 * (1 + 0.05*sin(wobble))
          p:      53.0 Hz     wobble: 11.3 Hz
  air   = lowpass(720 Hz, Q=0.7071) over white noise, per-channel decorrelated
  L/R   = hum + air*0.40      (hum shared/centered, air independent per channel)

SAMPLE_RATE = 44100 (Dsp.kt). No room EQ, brightness tilt, drift or space are
applied -- this is the raw generator at its default level ("standard tuning").
A short equal-power crossfade wraps the tail into the head so the file loops
seamlessly.
"""
import numpy as np
from scipy.signal import lfilter
import wave

SR = 44100
MINUTES = 20
XF = int(0.30 * SR)          # 300 ms seamless-loop crossfade
N = MINUTES * 60 * SR        # final length
M = N + XF                   # render a little extra to fold back in

# ---- Box fan low-pass Biquad coefficients (RBJ, matches Dsp.kt.lowpass) ----
def lowpass_coeffs(fc, q=0.7071, sr=SR):
    w0 = 2.0 * np.pi * fc / sr
    cw = np.cos(w0)
    alpha = np.sin(w0) / (2.0 * q)
    a0 = 1.0 + alpha
    b = np.array([(1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0])
    a = np.array([1.0, -2 * cw / a0, (1 - alpha) / a0])
    return b, a

b, a = lowpass_coeffs(720.0)

# ---- hum (vectorised) ------------------------------------------------------
i = np.arange(M, dtype=np.float64)
inc = 2.0 * np.pi * 53.0 / SR
incW = 2.0 * np.pi * 11.3 / SR
p = inc * i
w = incW * i
hum = (np.sin(p) * 0.5 + np.sin(2 * p) * 0.20 + np.sin(3 * p) * 0.08)
hum *= 0.30 * (1.0 + 0.05 * np.sin(w))
hum = hum.astype(np.float32)
del p, w, i

# ---- air: decorrelated per-channel low-passed white noise ------------------
rngL = np.random.default_rng(29)
rngR = np.random.default_rng(9731)
airL = lfilter(b, a, rngL.uniform(-1.0, 1.0, M)).astype(np.float32) * 0.40
airR = lfilter(b, a, rngR.uniform(-1.0, 1.0, M)).astype(np.float32) * 0.40

left = hum + airL
right = hum + airR
del airL, airR, hum

# ---- seamless loop: fold the extra XF tail back over the head --------------
t = np.linspace(0, 1, XF, endpoint=False, dtype=np.float32)
fade_in = np.sin(0.5 * np.pi * t)      # equal-power
fade_out = np.cos(0.5 * np.pi * t)
for ch in (left, right):
    ch[:XF] = ch[:XF] * fade_in + ch[N:N + XF] * fade_out
left = left[:N]
right = right[:N]

# ---- peak-normalise to -3 dBFS (single scalar, preserves hum/air balance) --
peak = max(np.abs(left).max(), np.abs(right).max())
gain = (10 ** (-3 / 20)) / peak
left *= gain
right *= gain
print(f"raw peak={peak:.4f}  gain={gain:.4f}  final peak=-3.0 dBFS")

# ---- interleave to int16 stereo and write WAV ------------------------------
inter = np.empty(N * 2, dtype=np.float32)
inter[0::2] = left
inter[1::2] = right
pcm = np.clip(inter * 32767.0, -32768, 32767).astype('<i2')

out_wav = "/tmp/claude-0/-workspace-Sleepslop/27ab5710-ced6-50f5-9027-0c91d160eb0c/scratchpad/boxfan-20min.wav"
with wave.open(out_wav, "wb") as wf:
    wf.setnchannels(2)
    wf.setsampwidth(2)
    wf.setframerate(SR)
    wf.writeframes(pcm.tobytes())
print("wrote", out_wav, f"({N/SR/60:.0f} min stereo {SR} Hz)")
