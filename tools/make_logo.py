"""Regenerate src/main/resources/logo.png — the icon the mods list shows.

    python3 tools/make_logo.py

The knot, drawn by the same rasteriser as the previews, over the deep-slate
the machine is built from. It replaces a picture of the Eye of Elsewhere: a
fine sprite, but the eye is one ingredient of one recipe, and the knot is what
the mod is about.
"""
import os

import numpy as np
from PIL import Image, ImageFilter
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'preview_knot.py')).read()
exec(src.split('os.makedirs(OUT')[0])          # definitions only, no file writing

# NOT `SIZE`: the preview module already uses that for the knot's own extent,
# and overwriting it framed the shot a hundred and fifty blocks wide.
PX = 128
SUPER = 8

# The preview frames the knot at 79% of the shot, which touches the edges once
# the bloom is added. Render it, shrink it, and paste it back centred so the
# logo has a margin — mod lists draw a border around this.
INSET = 0.84
knot = render(rot_x(GUI_TILT), PX * SUPER, walk(70), 0.30, 0.0, gain=0.45, bg=0.0)
inner = int(PX * INSET)
knot = knot.resize((inner, inner), Image.LANCZOS)
framed = Image.new("RGB", (PX, PX), (0, 0, 0))
framed.paste(knot, ((PX - inner) // 2, (PX - inner) // 2))
arr = np.asarray(framed, dtype=float)

# a soft bloom, which is what the additive pass looks like in the world
glow = np.asarray(Image.fromarray(np.uint8(np.clip(arr, 0, 255)))
                  .filter(ImageFilter.GaussianBlur(7)), dtype=float)

# the deep-slate ground the whole machine is built out of, lit from the middle
y, x = np.mgrid[0:PX, 0:PX]
falloff = np.clip(1.0 - np.hypot(x - PX / 2, y - PX / 2) / (PX * 0.62), 0, 1) ** 2
ground = np.stack([16 + 26 * falloff, 14 + 20 * falloff, 24 + 40 * falloff], axis=2)

out = ground + glow * 0.55 + arr * 1.15
out_path = os.path.join(os.path.dirname(here), 'src/main/resources/logo.png')
Image.fromarray(np.uint8(np.clip(out, 0, 255))).convert("RGBA").save(out_path)
print(out_path, PX, 'x', PX)
