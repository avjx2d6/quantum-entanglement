#!/usr/bin/env python3
"""Regenerate the ritual sound effects (OGG/Vorbis) from scratch.

    pip install numpy soundfile
    python3 tools/gen_sounds.py

Writes four files into assets/quantumitems/sounds/:

    ritual_hum.ogg     seamless 3s loop, played while the ring holds power
    ritual_riser.ogg   9s engine spool-up, the crescendo before collapse
    ritual_burst.ogg   2.6s detonation on a successful entanglement
    ritual_cancel.ogg  2.8s machine slammed to a halt on a failed ritual

Everything is synthesised — no samples, no external assets. The hum snaps
its partials to integer cycle counts so the loop is truly seamless. The
riser/burst/cancel share a single seeded RNG and MUST be generated in that
order (riser draws its noise bed first); reordering them changes the noise
in burst and cancel.
"""

import numpy as np
import soundfile as sf

SR = 44100
OUT = "src/main/resources/assets/quantumitems/sounds/"


def norm(x, peak=0.95):
    return (x / max(1e-9, np.max(np.abs(x)))) * peak


def saw_harmonics(phase, n=8):
    out = np.zeros_like(phase)
    for k in range(1, n + 1):
        out += np.sin(k * phase) / k
    return out


# --- steady hum, seamless 3s loop: detuned low sines, integer periods ---
T = 3.0
t = np.arange(int(SR * T)) / SR


def loop_freq(f):  # snap to integer cycles for a seamless loop
    return round(f * T) / T


hum = (0.5 * np.sin(2 * np.pi * loop_freq(82) * t)
       + 0.3 * np.sin(2 * np.pi * loop_freq(164) * t)
       + 0.15 * np.sin(2 * np.pi * loop_freq(246.5) * t)
       + 0.08 * np.sin(2 * np.pi * loop_freq(329) * t))
lfo = 1.0 + 0.12 * np.sin(2 * np.pi * loop_freq(0.66) * t)
sf.write(OUT + "ritual_hum.ogg", norm(hum * lfo, 0.55), SR, format="OGG", subtype="VORBIS")

# Shared seeded RNG. The three effects below draw from it in order; keep that
# order (riser, then burst, then cancel) or the noise in burst/cancel shifts.
rng = np.random.default_rng(3)

# --- engine spool-up riser, 9.0s (matches CRESCENDO 180 ticks) ---
T = 9.0
t = np.arange(int(SR * T)) / SR
# fundamental: exponential climb 26 -> 150 Hz, sawtooth body (engine meat)
f0 = 26 * (150 / 26) ** (t / T)
ph0 = 2 * np.pi * np.cumsum(f0) / SR
body = saw_harmonics(ph0, 9)
# chug: amplitude roughness accelerating 3 -> 22 Hz, fading as it smooths out
chug_rate = 3 + 19 * (t / T)
chug_ph = 2 * np.pi * np.cumsum(chug_rate) / SR
chug_depth = 0.45 * (1 - 0.6 * t / T)
body *= 1 - chug_depth * (0.5 + 0.5 * np.sin(chug_ph))
# turbine whine: joins halfway, 500 -> 3000 Hz
whine_amp = np.clip((t / T - 0.45) / 0.55, 0, 1) * 0.16
fw = 500 * (3000 / 500) ** (np.clip((t / T - 0.45) / 0.55, 0, 1))
whine = whine_amp * np.sin(2 * np.pi * np.cumsum(fw) / SR)
# air/noise bed growing
noise = rng.standard_normal(len(t))
noise = np.convolve(noise, np.ones(24) / 24, mode="same")   # crude lowpass
noise *= 0.25 * (t / T) ** 2
sig = (body + whine + noise) * (0.3 + 0.7 * (t / T) ** 1.4)
sig[-1500:] *= np.linspace(1, 0, 1500)
sf.write(OUT + "ritual_riser.ogg", norm(sig, 0.9), SR, format="OGG", subtype="VORBIS")

# --- burst: sub-drop + slam + long metallic ring, 2.6s, heavy ---
T = 2.6
t = np.arange(int(SR * T)) / SR
fsub = 95 * (24 / 95) ** np.clip(t / 1.1, 0, 1)
sub = np.sin(2 * np.pi * np.cumsum(fsub) / SR) * np.exp(-t * 1.6) * 1.6
slam = rng.standard_normal(len(t)) * np.exp(-t * 9) * 1.2
echo = np.zeros_like(t)
shift = int(0.33 * SR)
echo[shift:] = (rng.standard_normal(len(t) - shift)) * np.exp(-t[:-shift] * 7) * 0.5
ring = sum(a * np.sin(2 * np.pi * f * t) for f, a in
           ((820, 0.5), (1370, 0.35), (2210, 0.25), (3080, 0.15))) * np.exp(-t * 2.4)
crackle = (rng.random(len(t)) < 0.002).astype(float) * rng.standard_normal(len(t)) * np.exp(-t * 1.5) * 2.0
sig = sub + slam + echo + ring + crackle
sig = np.tanh(sig * 1.6)  # soft clip = perceived loudness
sf.write(OUT + "ritual_burst.ogg", norm(sig, 0.98), SR, format="OGG", subtype="VORBIS")

# --- cancel: an enormous machine slammed to a halt, 2.8s, deafening ---
T = 2.8
t = np.arange(int(SR * T)) / SR
# instant CLANG: metallic noise burst + deep thump
clang = rng.standard_normal(len(t)) * np.exp(-t * 22) * 2.0
thump = np.sin(2 * np.pi * 62 * t) * np.exp(-t * 5) * 1.6
# screech: inharmonic cluster gliding down 40% over the first second
partials = (1240, 1735, 2460)
screech = np.zeros_like(t)
glide = (1 - 0.4 * np.clip(t / 1.0, 0, 1))
for f in partials:
    ph = 2 * np.pi * np.cumsum(f * glide) / SR
    screech += np.sin(ph)
screech *= np.exp(-t * 2.6) * 0.5
# shudder: flywheel juddering to a stop — pulse train slowing 14 -> 2 Hz
jrate = 14 * (2 / 14) ** np.clip(t / 2.2, 0, 1)
jph = 2 * np.pi * np.cumsum(jrate) / SR
judder = (0.5 + 0.5 * np.sign(np.sin(jph))) * np.sin(2 * np.pi * 48 * t) * np.exp(-t * 1.1) * 1.1
# dying groan
groan_f = 58 * (34 / 58) ** np.clip(t / T, 0, 1)
groan = saw_harmonics(2 * np.pi * np.cumsum(groan_f) / SR, 5) * np.exp(-t * 1.4) * 0.5
sig = np.tanh((clang + thump + screech + judder + groan) * 1.5)
sf.write(OUT + "ritual_cancel.ogg", norm(sig, 0.98), SR, format="OGG", subtype="VORBIS")

print("4 sounds written to", OUT)
