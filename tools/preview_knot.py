#!/usr/bin/env python3
"""Preview the shard's knot without launching the game.

    pip install numpy pillow
    python3 tools/preview_knot.py

Rasterises exactly what EntangledKnotRenderer feeds the GPU: the same trefoil,
the same tube of shared rings with the same holonomy-corrected frame, and the
additive, depth-write-free blend of QuantumRenderTypes.RITUAL_BEAM — which is
order-independent, so no sorting is needed to get the real look.

Writes into preview/:

    knot_gui.png        the inventory angle, large
    knot_slot.png       the same at 16 px, upscaled — what a hotbar really shows
    knot_spin.png       the world angle through one turn around Y
    knot_writhe.png     consecutive ticks, to judge how alive it looks
    knot_film.png       the iridescence crawling, with nothing else moving
    knot_fury.png       agitation 0 -> 1, what the core does to it (arcs not shown)

Keep the constants below in step with EntangledKnotRenderer and with the
display block of models/item/quantum_knot.json, or the preview lies.
"""

import os

import numpy as np
from PIL import Image

# --- must match EntangledKnotRenderer ---
NODES = 40
SIZE = 0.34
RADIUS = 0.030
SIDES = 6
SIDES_GUI = 5
WALK_UNIT = 0.050
DECAY = 0.80
SPREAD = 0.70
HUE_MIN, HUE_MAX = 0.38, 0.88
TARGET_LUMA = 0.30
MAX_SATURATION = 0.95
VIEW_WEIGHT = 0.55
BAND_WEIGHT = 1.0 - VIEW_WEIGHT
BANDS = 2.0
WAVE = 0.110
WAVE_TURNS = 3.0
WALK_FURY = 5.0
FURY_SWELL = 0.9
WAVE_PERIOD = 8.0
DRIFT_PERIOD = 130.0

# --- must match the display block of item/quantum_knot.json ---
GUI_TILT = 90.0     # face-on: where the trefoil is legible
WORLD_TILT = 50.0   # the core, the floor and item frames spin this around Y

OUT = "preview/"


def unit(v):
    return v / np.linalg.norm(v)


def hsv_to_rgb(h, s, v):
    """Mth.hsvToRgb, as floats."""
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    return [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]


def _ramp():
    out = []
    for i in range(64):
        h = HUE_MIN + (HUE_MAX - HUE_MIN) * i / 63
        pure = np.dot((0.2126, 0.7152, 0.0722), hsv_to_rgb(h, 1.0, 1.0))
        s = min((1 - TARGET_LUMA) / (1 - pure), MAX_SATURATION)
        v = min(TARGET_LUMA / (1 - s * (1 - pure)), 1.0)
        out.append(hsv_to_rgb(h, s, v))
    return np.array(out)


RAMP = _ramp()


def film(along, facing, drift):
    band = 0.5 - 0.5 * np.cos((along * BANDS + drift) * 2 * np.pi)
    t = np.clip(VIEW_WEIGHT * (1 - facing) + BAND_WEIGHT * band, 0, 1)
    return RAMP[int(t * 63)]


def curve():
    """The (2,3) torus knot. Scaled so the widest axis spans 2 * SIZE."""
    t = 2 * np.pi * np.arange(NODES) / NODES
    r = 2 + np.cos(3 * t)
    p = np.stack([r * np.cos(2 * t), np.sin(3 * t), r * np.sin(2 * t)], 1)
    return p * (SIZE / np.abs(p).max())


BASE = curve()
_T = np.array([unit(BASE[(i + 1) % NODES] - BASE[(i - 1) % NODES]) for i in range(NODES)])
OFF_U = np.array([unit(np.cross(t, [0, 1, 0])) for t in _T])
OFF_V = np.array([unit(np.cross(_T[i], OFF_U[i])) for i in range(NODES)])


def rings(pts, radius, sides):
    """Shared rings on a parallel-transported frame, closed the way the mod does."""
    n = len(pts) - 1
    tan = np.array([unit(pts[(i + 1) % n] - pts[(i - 1) % n]) for i in range(n + 1)])
    seed = np.array([1.0, 0, 0]) if abs(tan[0][1]) > 0.9 else np.array([0.0, 1, 0])
    carried = unit(np.cross(tan[0], seed))
    u = []
    for i in range(n + 1):
        carried = unit(carried - tan[i] * np.dot(carried, tan[i]))
        u.append(carried.copy())
    u = np.array(u)
    # transport around a loop arrives rotated; unwind that evenly
    twist = np.arctan2(np.dot(tan[0], np.cross(u[n], u[0])), np.dot(u[n], u[0]))
    out = np.zeros((n + 1, sides, 3))
    nrm = np.zeros((n + 1, sides, 3))
    for i in range(n + 1):
        a = twist * i / n
        ui = u[i] * np.cos(a) + np.cross(tan[i], u[i]) * np.sin(a)
        vi = unit(np.cross(tan[i], ui))
        for k in range(sides):
            ang = 2 * np.pi * k / sides
            nrm[i, k] = ui * np.cos(ang) + vi * np.sin(ang)
            out[i, k] = pts[i] + nrm[i, k] * radius
    return out, nrm


def walk(ticks, seed=5):
    """Settle the shared random walk, then hand back its node offsets."""
    rng = np.random.default_rng(seed)
    x = np.zeros((NODES, 2))
    for _ in range(ticks):
        x = (x + (rng.random((NODES, 2)) - 0.5) * SPREAD) * DECAY
    return x


def nodes(offsets, time=0.0, fury=0.0):
    """fury is EntangledKnotRenderer.agitation: 0 in hand, 1 at the crescendo's end."""
    along = np.arange(NODES) / NODES
    ripple = (along * WAVE_TURNS - time / WAVE_PERIOD) * 2 * np.pi
    walk = WALK_UNIT * (1 + WALK_FURY * fury)
    a = offsets[:, :1] * walk + (WAVE * fury * np.cos(ripple))[:, None]
    b = offsets[:, 1:] * walk + (WAVE * fury * np.sin(ripple))[:, None]
    p = BASE + OFF_U * a + OFF_V * b
    return np.vstack([p, p[0:1]])


def brightness(phase, fury=0.0):
    b = np.zeros(NODES + 1)
    for i in range(NODES):
        d = abs(((i / NODES) - phase + 1.5) % 1.0 - 0.5)
        head = np.clip(1.0 - d * 5.0, 0, 1)
        b[i] = np.clip((0.55 + head * head * 0.65) * (1 + FURY_SWELL * fury), 0.34, 2.4)
    b[NODES] = b[0]
    return b


def rot_x(deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _triangle(img, p, q, r, cp, cq, cr, gain):
    px = img.shape[0]
    lo_x = max(int(min(p[0], q[0], r[0])), 0)
    hi_x = min(int(max(p[0], q[0], r[0])) + 1, px - 1)
    lo_y = max(int(min(p[1], q[1], r[1])), 0)
    hi_y = min(int(max(p[1], q[1], r[1])) + 1, px - 1)
    if hi_x < lo_x or hi_y < lo_y:
        return
    ys, xs = np.mgrid[lo_y:hi_y + 1, lo_x:hi_x + 1]
    x, y = xs + 0.5, ys + 0.5
    den = (q[1] - r[1]) * (p[0] - r[0]) + (r[0] - q[0]) * (p[1] - r[1])
    if abs(den) < 1e-12:
        return
    l0 = ((q[1] - r[1]) * (x - r[0]) + (r[0] - q[0]) * (y - r[1])) / den
    l1 = ((r[1] - p[1]) * (x - r[0]) + (p[0] - r[0]) * (y - r[1])) / den
    l2 = 1 - l0 - l1
    m = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
    if not m.any():
        return
    c = (l0[..., None] * cp + l1[..., None] * cq + l2[..., None] * cr)[m]
    img[px - 1 - ys[m], xs[m]] += c * gain


def render(matrix, px, offsets, phase=0.0, drift=0.0, sides=SIDES, gain=0.40, bg=0.055,
           time=0.0, fury=0.0):
    pts = nodes(offsets, time, fury)
    ring, normals = rings(pts, RADIUS * (1 + FURY_SWELL * fury), sides)
    ring = ring @ matrix.T
    normals = normals @ matrix.T          # the tint works in view space here
    bright = brightness(phase, fury)
    # the eye is down +Z once everything has been rotated into view
    eye = np.array([0.0, 0.0, 1.0])
    col = np.zeros((NODES + 1, sides, 3))
    for i in range(NODES + 1):
        for k in range(sides):
            col[i, k] = film(i / NODES, abs(np.dot(normals[i, k], eye)), drift) * bright[i]
    img = np.full((px, px, 3), bg)
    half = SIZE * 1.15
    xy = (ring[:, :, :2] / (2 * half) + 0.5) * px
    for i in range(NODES):
        for k in range(sides):
            k2 = (k + 1) % sides
            a, b, c, d = xy[i, k], xy[i, k2], xy[i + 1, k2], xy[i + 1, k]
            _triangle(img, a, b, c, col[i, k], col[i, k2], col[i + 1, k2], gain)
            _triangle(img, a, c, d, col[i, k], col[i + 1, k2], col[i + 1, k], gain)
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def strip(images, path):
    w, h = images[0].size
    sheet = Image.new("RGB", (w * len(images), h))
    for i, im in enumerate(images):
        sheet.paste(im, (i * w, 0))
    sheet.save(OUT + path)


os.makedirs(OUT, exist_ok=True)
settled = walk(70)

render(rot_x(GUI_TILT), 512, settled, 0.30, 0.0).save(OUT + "knot_gui.png")
render(rot_x(GUI_TILT), 16, settled, 0.30, 0.0, sides=SIDES_GUI) \
    .resize((256, 256), Image.NEAREST).save(OUT + "knot_slot.png")
strip([render(rot_y(a) @ rot_x(WORLD_TILT), 220, settled, a / 360.0, a / 360.0)
       for a in range(0, 360, 45)], "knot_spin.png")
strip([render(rot_x(GUI_TILT), 220, walk(70 + k * 3), 0.30, 0.0, time=k * 3.0)
       for k in range(6)], "knot_writhe.png")
strip([render(rot_x(GUI_TILT), 220, walk(70 + k * 4), 0.30, 0.0, time=k * 4.0, fury=f)
       for k, f in enumerate((0.0, 0.25, 0.45, 0.65, 0.85, 1.0))], "knot_fury.png")
strip([render(rot_x(GUI_TILT), 220, settled, 0.30, d / 6.0) for d in range(6)],
      "knot_film.png")

print("previews written to", OUT)
