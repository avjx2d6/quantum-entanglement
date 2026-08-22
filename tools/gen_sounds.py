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

# --- burst: sub-drop + muffled slam + low metallic ring, 2.6s ---
# Softness here is structural, not a level. A full-band noise crack puts most
# of its energy in 2-8 kHz, which is exactly where the ear hurts, and tanh at
# high drive pushes even more up there. So the slam is low-passed into a thump,
# the ring loses its shrillest partials, and the drive comes down — the weight
# is carried by the sub-drop, which you feel rather than hear.
# The RNG is drawn in the same order and the same sizes as before: cancel is
# generated from what is left of this stream and must not shift.
T = 2.6
t = np.arange(int(SR * T)) / SR
fsub = 95 * (24 / 95) ** np.clip(t / 1.1, 0, 1)
# 1.2, not higher: through the tanh a louder sub saturates the whole clip and
# the burst turns into a flat two-second wash with no impact shape left
sub = np.sin(2 * np.pi * np.cumsum(fsub) / SR) * np.exp(-t * 1.5) * 1.2
# Two layers off ONE noise draw. The body is heavily low-passed (40-sample
# average, roughly a 1 kHz corner) and rings on for a while; the crack is only
# gently rolled off (8 samples, ~5 kHz) and is gone in about 45 ms. A crack
# that brief answers the riser without the ear having time to mind it — what
# hurt before was broadband noise SUSTAINED, not the fact of a transient.
slam_raw = rng.standard_normal(len(t))
slam_body = np.convolve(slam_raw, np.ones(40) / 40, mode="same") \
    * np.exp(-t * 6) * np.clip(t / 0.02, 0, 1) * 5.0
slam_crack = np.convolve(slam_raw, np.ones(8) / 8, mode="same") \
    * np.exp(-t * 13) * np.clip(t / 0.006, 0, 1) * 1.7
slam = slam_body + slam_crack
# The reflection used to land a third of a second after the impact, which is
# long enough for the ear to hear a SECOND, separate hit rather than the room
# answering the first — the "double hit". It starts at 100 ms now and swells
# instead of striking, so it fuses into the tail. The draw keeps its original
# size because cancel is generated from the rest of this stream.
shift = int(0.33 * SR)
echo_raw = rng.standard_normal(len(t) - shift)
echo_t = np.arange(len(echo_raw)) / SR
echo = np.zeros_like(t)
place = int(0.10 * SR)
echo[place:place + len(echo_raw)] = np.convolve(echo_raw, np.ones(120) / 120, mode="same") \
    * np.clip(echo_t / 0.12, 0, 1) * np.exp(-echo_t * 3.2) * 4.0
# ring: a low partial added for body, the shrill top ones nearly gone
ring = sum(a * np.sin(2 * np.pi * f * t) for f, a in
           ((560, 0.45), (820, 0.38), (1370, 0.22), (2210, 0.16), (3080, 0.06))) * np.exp(-t * 2.2)
crackle = (rng.random(len(t)) < 0.002).astype(float) * rng.standard_normal(len(t)) * np.exp(-t * 1.5) * 0.35
sig = sub + slam + echo + ring + crackle
sig = np.tanh(sig * 1.3)  # gentler than the original 1.6, still with some bite
# 0.75 keeps the decoded peak at 0.96 — Vorbis overshoots on the transient, and
# the original 0.98 decoded to 1.71, i.e. it clipped on playback
sf.write(OUT + "ritual_burst.ogg", norm(sig, 0.75), SR, format="OGG", subtype="VORBIS")

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
