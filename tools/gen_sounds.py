#!/usr/bin/env python3
"""
Quantum Entanglement — ritual sound synthesizer.

Every ritual sound is generated from scratch with numpy and written as
OGG/Vorbis (the format Minecraft uses) with soundfile. Nothing is recorded or
sampled — it is all math over a signal buffer.

Tweak the CONFIG blocks at the top of each build_* function, run the file, and
it regenerates the four .ogg files.

    pip install numpy soundfile
    python gen_sounds.py

By default it writes to ./sounds_out/ so it does NOT overwrite the shipped,
already-tuned assets. When you are happy with a new take, point OUT_DIR at
    src/main/resources/assets/quantumitems/sounds/
and run again.

Sounds:
    ritual_hum     seamless low drone looped under the early ritual phases
    ritual_riser   ~9s "accelerating engine" spool-up for the crescendo
    ritual_burst   the success snap: sub-drop + impact + metallic ring
    ritual_cancel  the failure: a huge machine grinding to a halt
"""

import os
import numpy as np
import soundfile as sf

SR = 48000                      # sample rate
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sounds_out")
# To overwrite the real assets instead, set e.g.:
# OUT_DIR = "src/main/resources/assets/quantumitems/sounds"


# ---------------------------------------------------------------------------
# DSP helpers
# ---------------------------------------------------------------------------

def t_axis(seconds):
    """Time array of the given length."""
    return np.linspace(0, seconds, int(seconds * SR), endpoint=False)


def sweep(freqs):
    """
    A sine whose instantaneous frequency follows `freqs` (an array, one value
    per sample). Phase is the running integral of frequency — this is the ONLY
    click-free way to sweep pitch; sin(2*pi*f(t)*t) is wrong and clicks.
    """
    phase = 2 * np.pi * np.cumsum(freqs) / SR
    return np.sin(phase), phase


def exp_freq(f0, f1, n):
    """Exponential frequency ramp from f0 to f1 over n samples (musical sweep)."""
    return f0 * (f1 / f0) ** np.linspace(0, 1, n)


def adsr(n, a=0.01, d=0.1, s=0.7, r=0.2):
    """Simple attack/decay/sustain/release envelope, lengths in seconds."""
    a, d, r = int(a * SR), int(d * SR), int(r * SR)
    s_len = max(0, n - a - d - r)
    env = np.concatenate([
        np.linspace(0, 1, a, endpoint=False),
        np.linspace(1, s, d, endpoint=False),
        np.full(s_len, s),
        np.linspace(s, 0, r),
    ])
    return _fit(env, n)


def exp_decay(n, tau):
    """Exponential decay envelope with time constant tau (seconds)."""
    return np.exp(-t_axis(n / SR) / tau)


def noise(n):
    return np.random.uniform(-1, 1, n)


def lowpass(x, cutoff):
    """Cheap moving-average low-pass (vectorized, no scipy)."""
    w = max(1, int(SR / max(1.0, cutoff)))
    k = np.ones(w) / w
    return np.convolve(x, k, mode="same")


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def bandpass(x, low, high):
    return highpass(lowpass(x, high), low)


def soft_clip(x, drive=2.0):
    """tanh saturation — adds grit and glue without harsh digital clipping."""
    return np.tanh(drive * x) / np.tanh(drive)


def fades(x, fade_in=0.01, fade_out=0.05):
    """Linear fades at the ends so a one-shot never clicks on/off."""
    x = x.copy()
    fi, fo = int(fade_in * SR), int(fade_out * SR)
    if fi:
        x[:fi] *= np.linspace(0, 1, fi)
    if fo:
        x[-fo:] *= np.linspace(1, 0, fo)
    return x


def normalize(x, peak=0.95):
    m = np.max(np.abs(x))
    return x / m * peak if m > 0 else x


def snap(freq, loop_seconds):
    """Round a frequency so it completes a whole number of cycles in the loop
    (the trick that makes a drone loop seamlessly)."""
    return max(1, round(freq * loop_seconds)) / loop_seconds


def write(name, data):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name + ".ogg")
    sf.write(path, data.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    print(f"  wrote {path}  ({len(data)/SR:.2f}s)")


# ---------------------------------------------------------------------------
# The four sounds
# ---------------------------------------------------------------------------

def build_hum():
    # CONFIG
    LOOP = 2.0                    # loop length (s) — keep short, it repeats
    BASE = 62.0                   # fundamental (Hz)
    PARTIALS = [1, 2, 3, 5]       # harmonics
    GAINS = [1.0, 0.4, 0.25, 0.12]
    DETUNE = 0.5                  # Hz spread for a thick, beating drone
    BREATH_HZ = 0.5              # slow amplitude LFO
    BREATH_DEPTH = 0.15

    base = snap(BASE, LOOP)
    t = t_axis(LOOP)
    sig = np.zeros_like(t)
    for p, g in zip(PARTIALS, GAINS):
        for det in (-DETUNE, 0.0, DETUNE):
            f = snap(base * p + det, LOOP)   # snap every voice → seamless
            sig += g * np.sin(2 * np.pi * f * t)
    lfo = 1 + BREATH_DEPTH * np.sin(2 * np.pi * snap(BREATH_HZ, LOOP) * t)
    sig *= lfo
    sig = soft_clip(sig, 1.3)
    # NO end fades — that would break the loop; seamlessness comes from snap().
    write("ritual_hum", normalize(sig, 0.7))


def build_riser():
    # CONFIG
    DUR = 9.0
    F_START, F_END = 26.0, 150.0     # engine RPM sweep (as frequency)
    HARMONICS = [1, 2, 3, 4]
    HGAINS = [1.0, 0.6, 0.35, 0.2]
    CHUG_RATIO = 0.5                 # chug pulse rate relative to base freq
    CHUG_DEPTH = 0.6
    TURBINE_MULT = 9.0              # whine partial, rises faster
    TURBINE_GAIN = 0.18
    DRIVE = 2.2

    n = int(DUR * SR)
    f = exp_freq(F_START, F_END, n)
    sig = np.zeros(n)
    for h, g in zip(HARMONICS, HGAINS):
        s, _ = sweep(f * h)
        sig += g * s
    # chug: rectified LFO tied to the (rising) rotation rate → piston putt-putt
    chug_phase = 2 * np.pi * np.cumsum(f * CHUG_RATIO) / SR
    chug = 1 - CHUG_DEPTH * (0.5 + 0.5 * np.cos(chug_phase))
    sig *= chug
    # turbine whine fading in over the sweep
    turb, _ = sweep(f * TURBINE_MULT)
    sig += TURBINE_GAIN * turb * np.linspace(0, 1, n) ** 2
    sig = soft_clip(sig, DRIVE)
    sig *= np.linspace(0.3, 1.0, n)      # overall level ramps up
    write("ritual_riser", fades(normalize(sig, 0.95), 0.03, 0.08))


def build_burst():
    # CONFIG
    DUR = 1.6
    SUB_FROM, SUB_TO, SUB_TAU = 150.0, 30.0, 0.35   # gut-punch sub drop
    SLAM_TAU = 0.06                                  # impact noise decay
    SLAM_BAND = (200, 3500)
    RING_BASE = 520.0
    RING_RATIOS = [1.0, 2.76, 5.40, 8.93]           # inharmonic "bell"
    RING_TAU = 0.9
    MIX = dict(sub=1.0, slam=0.8, ring=0.5)

    n = int(DUR * SR)
    sub_n = int(SUB_TAU * 3 * SR)
    sub_f = exp_freq(SUB_FROM, SUB_TO, sub_n)
    sub, _ = sweep(sub_f)
    sub = _fit(sub * exp_decay(sub_n, SUB_TAU), n)
    slam = bandpass(noise(n), *SLAM_BAND) * exp_decay(n, SLAM_TAU)
    ring = np.zeros(n)
    tt = t_axis(DUR)
    for r in RING_RATIOS:
        ring += np.sin(2 * np.pi * RING_BASE * r * tt)
    ring *= exp_decay(n, RING_TAU) / len(RING_RATIOS)
    sig = MIX["sub"] * sub + MIX["slam"] * slam + MIX["ring"] * ring
    sig = soft_clip(sig, 1.8)
    write("ritual_burst", fades(normalize(sig, 0.97), 0.002, 0.2))


def build_cancel():
    # CONFIG
    DUR = 2.6
    F_FROM, F_TO = 150.0, 18.0       # engine spinning DOWN to a halt
    HARMONICS = [1, 2, 3]
    HGAINS = [1.0, 0.5, 0.25]
    CHUG_RATIO = 0.5
    CHUG_DEPTH = 0.7
    THUD_F, THUD_TAU = 42.0, 0.5     # final heavy drop
    GRIND_BAND = (120, 1600)
    GRIND_GAIN = 0.25
    DRIVE = 2.5

    n = int(DUR * SR)
    f = exp_freq(F_FROM, F_TO, n)
    sig = np.zeros(n)
    for h, g in zip(HARMONICS, HGAINS):
        s, _ = sweep(f * h)
        sig += g * s
    chug_phase = 2 * np.pi * np.cumsum(f * CHUG_RATIO) / SR
    sig *= 1 - CHUG_DEPTH * (0.5 + 0.5 * np.cos(chug_phase))
    # grinding metal, wobbling as it slows
    grind = bandpass(noise(n), *GRIND_BAND) * (0.6 + 0.4 * np.sin(chug_phase))
    sig += GRIND_GAIN * grind
    # a final thud near the end
    thud_n = int(THUD_TAU * 3 * SR)
    thud, _ = sweep(exp_freq(THUD_F, THUD_F * 0.6, thud_n))
    thud *= exp_decay(thud_n, THUD_TAU)
    sig[-thud_n:] += 0.9 * thud
    sig = soft_clip(sig, DRIVE)
    sig *= np.linspace(1.0, 0.85, n)
    write("ritual_cancel", fades(normalize(sig, 0.95), 0.03, 0.15))


# ---------------------------------------------------------------------------

def _fit(x, n):
    """Trim or zero-pad an array to exactly n samples."""
    if len(x) >= n:
        return x[:n]
    return np.concatenate([x, np.zeros(n - len(x))])


if __name__ == "__main__":
    print(f"Synthesizing into {OUT_DIR}/ ...")
    build_hum()
    build_riser()
    build_burst()
    build_cancel()
    print("Done. Drop the .ogg files into the mod's sounds/ folder when happy.")
