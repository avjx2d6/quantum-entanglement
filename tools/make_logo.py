"""Regenerate src/main/resources/logo.png — the icon mod lists and pages show.

    python3 tools/make_logo.py

Not a picture of an item. The mark is the RITUAL: four resonators standing on a
ring with their beams converging on the core above the middle — three drawing
in and one giving back, which is the machine's one rule drawn rather than
written. It is made of the same strand geometry the mod puts on screen, so the
identity comes from the thing a player actually sees, and it stays legible at
the 32 pixels a mod list gives it, where a knot is a smudge.

The background is transparent and the mark does not touch the frame. An icon is
composited onto somebody else's page — a square of our own slate would show up
as a square of our own slate, and every list that rounds the corners would cut
it. Alpha comes from the light itself, so the glow fades out instead of ending.
"""
import os

import numpy as np
from PIL import Image, ImageFilter

here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'preview_knot.py')).read()
exec(src.split('os.makedirs(OUT')[0])          # definitions only, no file writing

PX = 256
SUPER = 3                # rendered this many times over, then shrunk

# Straight down. A three-quarter view was tried first and the four beams read
# as the legs of a spider — an icon is looked at for a third of a second, and
# whatever shape it happens to make is the shape it means. From above there is
# only one reading: four points, a ring, and something in the middle.
SPIN = 45.0              # corners on the diagonals, which fills a square frame

# The mod's own three, and the reason there are two of them here: a ritual
# takes from the loaded resonators and gives back through the empty one.
COLOR_IN = np.array([0x21, 0xBE, 0xD9]) / 255.0
COLOR_OUT = np.array([0xE0, 0xB0, 0x1B]) / 255.0
COLOR_CORE = np.array([0x82, 0x43, 0xE0]) / 255.0

RING = 1.0               # resonators this far out
STAND = 0.16             # the resonator's own bead
BEAM_R = 0.048
STAND_R = 0.105
CORE_R = 0.150
CIRCLE_R = 0.026         # the ritual circle: present, not loud
NODES_PER_BEAM = 12
CIRCLE_NODES = 96
WOBBLE = 0.052


def open_rings(pts, radius, sides):
    """rings() for a path that does not close on itself."""
    n = len(pts) - 1
    tan = np.zeros((n + 1, 3))
    tan[0] = unit(pts[1] - pts[0])
    tan[n] = unit(pts[n] - pts[n - 1])
    for i in range(1, n):
        tan[i] = unit(pts[i + 1] - pts[i - 1])
    seed = np.array([1.0, 0, 0]) if abs(tan[0][1]) > 0.9 else np.array([0.0, 1, 0])
    carried = unit(np.cross(tan[0], seed))
    out = np.zeros((n + 1, sides, 3))
    for i in range(n + 1):
        carried = unit(carried - tan[i] * np.dot(carried, tan[i]))
        vi = unit(np.cross(tan[i], carried))
        for k in range(sides):
            ang = 2 * np.pi * k / sides
            out[i, k] = pts[i] + (carried * np.cos(ang) + vi * np.sin(ang)) * radius
    return out


def tube(img, matrix, pts, colour, bright, radius, half, gain=0.5, sides=7):
    """One strand, flat-coloured, drawn with the preview's own rasteriser."""
    ring = open_rings(np.asarray(pts, dtype=float), radius, sides) @ matrix.T
    px = img.shape[0]
    xy = (ring[:, :, :2] / (2 * half) + 0.5) * px
    for i in range(len(pts) - 1):
        for k in range(sides):
            k2 = (k + 1) % sides
            ca, cb = colour * bright[i], colour * bright[i + 1]
            _triangle(img, xy[i, k], xy[i, k2], xy[i + 1, k2], ca, ca, cb, gain)
            _triangle(img, xy[i, k], xy[i + 1, k2], xy[i + 1, k], ca, cb, cb, gain)


def beam(phase, foot, top):
    """A resonator's beam: pinned at both ends, bowed in between.

    The real thing is a random walk, and at this size a random walk is a
    zigzag — it reads as a cartoon lightning bolt rather than as a taut line
    with some life in it. A bow with one soft kink in it says the same thing at
    32 pixels and survives being shrunk further.
    """
    foot, top = np.asarray(foot, dtype=float), np.asarray(top, dtype=float)
    span = top - foot
    side = unit(np.cross(span, [0, 1, 0]))
    t = np.arange(NODES_PER_BEAM + 1) / NODES_PER_BEAM
    swing = np.sin(np.pi * t) * (np.sin(phase + t * 2.2 * np.pi) * 0.6 + 0.7)
    pts = foot + span * t[:, None] + side * (WOBBLE * swing)[:, None]
    # brightest where it arrives, so the eye is pulled to the core
    return pts, 0.42 + 0.80 * t ** 2


def main():
    px = PX * SUPER
    matrix = rot_x(90.0) @ rot_y(SPIN)   # rot_x(90) lays the ground plane flat on screen
    img = np.zeros((px, px, 3))
    half = 1.30                                  # framing: leaves the margin
    centre = np.zeros(3)

    # The ritual circle. The mod already draws one — it is what a completed
    # ritual throws off — and it is what stops the mark reading as a plus sign.
    # Breathed in and out very slightly, and brighter where a beam meets it: a
    # true circle at this weight looks like clip art, and this is supposed to be
    # made of the same living line as everything else.
    a = np.linspace(0, 2 * np.pi, CIRCLE_NODES + 1)
    # Small. At 2% it stopped reading as a circle with life in it and started
    # reading as a circle drawn badly, which is a different thing entirely.
    r = RING * (1 + 0.007 * np.sin(a * 5 + 0.7) + 0.004 * np.sin(a * 11))
    ring = np.stack([r * np.cos(a), np.zeros_like(a), r * np.sin(a)], axis=1)
    tube(img, matrix, ring, COLOR_CORE, 0.72 + 0.30 * np.abs(np.cos(2 * a)),
         CIRCLE_R, half, gain=0.45, sides=5)

    for leg in range(4):
        a = leg * np.pi / 2
        foot = np.array([RING * np.cos(a), 0.0, RING * np.sin(a)])
        colour = COLOR_OUT if leg == 3 else COLOR_IN
        inward = unit(centre - foot)
        # the resonator itself: a bead on the circle, sitting where the beam
        # leaves rather than being a fifth shape to recognise
        tube(img, matrix, [foot - inward * STAND, foot + inward * STAND * 0.4], colour,
             np.array([1.15, 1.15]), STAND_R, half, gain=0.6)
        # stopped at the core's edge rather than run through it: four lines
        # meeting in a point make an X, and an X is what the eye takes away
        pts, bright = beam(leg * 1.9, foot + inward * STAND * 0.4,
                           centre - inward * CORE_R * 0.9)
        tube(img, matrix, pts, colour, bright, BEAM_R, half)

    # The core: a bead where everything meets. Kept off the ceiling on purpose —
    # at brightness 1.5 it clipped to white and the one violet in the mark was
    # gone, which is the whole difference between a light source and a hole.
    tube(img, matrix, [centre - [0.10, 0, 0], centre + [0.10, 0, 0]], COLOR_CORE,
         np.array([1.05, 1.05]), CORE_R, half, gain=0.62)

    img = np.asarray(Image.fromarray(np.uint8(np.clip(img, 0, 1) * 255))
                     .resize((PX, PX), Image.LANCZOS), dtype=float) / 255.0

    # Additive light on nothing: the halo is the same light, spread out.
    glow = np.asarray(Image.fromarray(np.uint8(np.clip(img, 0, 1) * 255))
                      .filter(ImageFilter.GaussianBlur(PX / 40)), dtype=float) / 255.0
    lit = np.clip(img + glow * 0.75, 0, 1)

    # Alpha IS the brightness, and the colour is un-premultiplied back out of
    # it, so the mark keeps its hue over a white page and a dark one alike.
    alpha = np.clip(lit.max(axis=2) * 1.35, 0, 1)
    rgb = np.divide(lit, np.maximum(alpha, 1e-6)[..., None])
    out = np.dstack([np.clip(rgb, 0, 1) * 255, alpha * 255])

    path = os.path.join(os.path.dirname(here), 'src/main/resources/logo.png')
    Image.fromarray(np.uint8(out), mode="RGBA").save(path)
    print(path, PX, 'x', PX, '— alpha covers %.0f%% of the frame'
          % (100 * (alpha > 0.02).mean()))


if __name__ == '__main__':
    main()
