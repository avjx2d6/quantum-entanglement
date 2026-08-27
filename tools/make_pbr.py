"""Generate LabPBR specular maps beside every texture the mod ships.

    python3 tools/make_pbr.py [--preview DIR]

A shader pack looks for two extra files next to each texture: {name}_n.png for
normals and {name}_s.png for the surface itself. Only the second is written
here — see the note at the bottom about why the normals are not.

The rule is one sentence: the amethyst shines and the deepslate does not. That
is decided per pixel from the colour of the texture itself rather than from a
hand-painted mask, so the maps cannot drift out of step with the art. Repaint a
texture, rerun this, done.

LabPBR packs four unrelated things into one image, and the packing is not
guessable, so it is spelled out here:

    R  perceptual smoothness       roughness = (1 - r)^2
    G  reflectance                 0..229 is a linear F0 = g/255;
                                   230..255 index a table of METALS instead
    B  porosity and subsurface     0..64 is porosity = b/64,
                                   65..255 is subsurface = (b-65)/190
    A  emission                    a/255 ... EXCEPT that 255 means NONE

That last one is the trap worth repeating: a plain opaque alpha channel is the
"does not glow" value, not the "glows fully" one, which is why every byte of
alpha this script writes is deliberate. It is also why the files must be saved
without any PNG optimiser that decides an all-255 alpha channel is redundant
and drops it — that would turn "no emission" into "no alpha", and packs read a
missing alpha as full emission.

Nothing here affects vanilla. Without a shader pack these files are never
opened; they cost a few hundred bytes each in the jar and that is all.

WHAT THESE FILES CANNOT DO, which cost an evening to establish and is worth
writing down. A pack does not have to read them, and Photon ships with
`//#define SPECULAR_MAPPING` — commented out, off — so by default it reads none
of this. What it does instead is HARDCODED_SPECULAR: a table of block names in
its own `block.properties`,

    block.10013 = diamond_block ... amethyst_block budding_amethyst

which gets material 13, "Gems": f0 0.25 and screen-space reflections forced on.
That is where the mirror on a vanilla amethyst floor comes from, and no texture
we ship can put a modded block on that list — the list belongs to the pack.
There is no tag to join either; the file defines one amethyst tag alias and
then never uses it.

So the honest ceiling for a mod is this: with Specular Mapping switched on,
these maps are read and are worth having. With it off, they do nothing at all,
and that is the pack's call rather than a fault in the maps. Photon's LabPBR
decoder is otherwise faithful — including the emission guard
`specular_map.a * float(specular_map.a != 1.0)`, which is the alpha rule below
implemented on their side.

And switching it on is not free for everyone else, which is worth knowing
before recommending it. In `d4_deferred_shading` the hardcoded table runs first
and the specular texture OVERWRITES it, so a block whose resource pack ships no
_s map gets Iris's default of all zeros: roughness (1-0)^2 = 1, fully rough.
The f0 survives, because that line is a max — but

    ssr_multiplier = step(0.01, f0 - f0 * roughness * SSR_ROUGHNESS_THRESHOLD)

with roughness at 1 and the threshold at 2 goes negative, and the reflection is
gone. That is why turning Specular Mapping on takes the mirror off vanilla
amethyst at the same moment it makes these maps work: the pack's hardcoded gem
is beaten by a texture that says nothing, because saying nothing is still
saying something once the texture is being read at all.
"""

import argparse
import glob
import os

import numpy as np
from PIL import Image

TEXTURES = "src/main/resources/assets/quantumitems/textures/**/*.png"

# --- what counts as amethyst ---------------------------------------------
#
# Hue alone is not enough: the deepslate is a neutral grey, and a neutral grey
# has a hue, it is just meaningless. Both terms have to agree — the pixel must
# lean violet AND actually have some colour in it — and both are ramps rather
# than thresholds, so a pale lavender facet comes out part way between crystal
# and stone instead of falling off a cliff into one of them. A hard mask reads
# as a decal cut out of the block; this reads as one material shading into
# another, which is what the art does.
HUE_CORE = (250.0, 300.0)      # unmistakably amethyst
HUE_EDGE = (225.0, 325.0)      # ... fading out to nothing here
CHROMA_LO = 0.045              # below this it is grey, whatever its hue says
CHROMA_HI = 0.150

# The one place the colour test is simply wrong: a crystal's own highlight is
# painted almost white, so it has no chroma and no hue to read, and the test
# calls the brightest part of the amethyst deepslate. Rescued by where it is
# rather than what colour it is — bright, and surrounded by crystal. Two steps
# of growth reaches the middle of the largest highlight in these textures.
HIGHLIGHT_LO = 0.72
HIGHLIGHT_HI = 0.92
HIGHLIGHT_REACH = 2

# --- the two materials ----------------------------------------------------
#
# Amethyst is quartz. Its real F0 is about 0.05, which is a very ordinary
# number — what makes a crystal look like a crystal is not strong reflection
# but SMOOTH reflection, so nearly all of the effect is in the red channel.
# Pushing F0 up instead is the usual way to end up with something that reads as
# wet plastic.
CRYSTAL_SMOOTH = 0.78
# Quartz really has an F0 near 0.05, and 0.05 was what this said first. It was
# right and it looked wrong: packs do not use the real number for gems. Photon
# hands amethyst 0.25, five times physical, because that is what makes a gem
# read as a gem — and a resonator standing on an amethyst floor is compared
# against the block next to it, not against a reference table. This sits just
# under the pack's own figure so it is at home beside vanilla amethyst without
# out-shining it somewhere more physical.
CRYSTAL_F0 = 0.22
# Amethyst is translucent, and subsurface is what carries that. Kept low: this
# channel goes waxy fast, and a waxy crystal is worse than a flat one.
CRYSTAL_SSS = 0.22
# Faint, on purpose. The core's real glow is its BLOCK LIGHT, which the ritual
# ramps from nothing to full — that is a different mechanism and it stacks on
# top of this. A large baked emission would light an idle machine as brightly
# as one mid-ritual and flatten the whole ramp, so this is only enough for the
# crystal to catch a pack's bloom while it sits there doing nothing.
CRYSTAL_EMISSION = 0.15

# Deepslate: rough, porous, and no more reflective than any other stone. Not
# zero smoothness — a stone with none at all looks like felt under a shader.
STONE_SMOOTH = 0.14
STONE_F0 = 0.04
STONE_POROSITY = 0.45

# Facet-to-facet variation, taken from the texture's own brightness. Flat
# channels are what make PBR look like plastic film laid over the art; the
# highlights already vary in the colour map, and this lets the reflection vary
# with them.
SMOOTH_BY_VALUE = 0.14


def ramp(x, lo, hi):
    """0 below lo, 1 above hi, smoothstep between."""
    t = np.clip((x - lo) / (hi - lo), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def crystalness(rgb):
    """Per pixel, 0 for deepslate and 1 for amethyst."""
    hi = rgb.max(axis=2)
    lo = rgb.min(axis=2)
    chroma = hi - lo

    with np.errstate(divide="ignore", invalid="ignore"):
        r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
        hue = np.where(hi == r, (g - b) / chroma % 6,
                       np.where(hi == g, (b - r) / chroma + 2,
                                (r - g) / chroma + 4)) * 60.0
    hue = np.where(chroma < 1e-6, 0.0, hue)

    # Two-sided window on hue: up into the band from below, down out of it above.
    inside = (ramp(hue, HUE_EDGE[0], HUE_CORE[0])
              * (1.0 - ramp(hue, HUE_CORE[1], HUE_EDGE[1])))
    crystal = inside * ramp(chroma, CHROMA_LO, CHROMA_HI)

    near = crystal
    for _ in range(HIGHLIGHT_REACH):
        near = grow(near)
    return np.maximum(crystal, near * ramp(hi, HIGHLIGHT_LO, HIGHLIGHT_HI))


def grow(mask):
    """One step of 3x3 dilation, edges held rather than wrapped."""
    padded = np.pad(mask, 1, mode="edge")
    out = mask
    for dy in (0, 1, 2):
        for dx in (0, 1, 2):
            out = np.maximum(out, padded[dy:dy + mask.shape[0], dx:dx + mask.shape[1]])
    return out


def specular(image):
    rgba = np.asarray(image.convert("RGBA"), dtype=float) / 255.0
    rgb = rgba[..., :3]
    crystal = crystalness(rgb)
    value = rgb.max(axis=2)

    smooth = ((1 - crystal) * STONE_SMOOTH + crystal * CRYSTAL_SMOOTH
              + (value - 0.5) * SMOOTH_BY_VALUE * crystal)
    f0 = (1 - crystal) * STONE_F0 + crystal * CRYSTAL_F0

    # Porosity and subsurface share one channel and cannot be mixed: a value
    # halfway between "porous stone" and "translucent crystal" is not halfway
    # between the two looks, it is a fully porous stone. So the channel is
    # switched, at the point where a pixel is more crystal than not.
    porosity = np.round(np.clip(STONE_POROSITY, 0, 1) * 64.0)
    sss = np.round(65.0 + np.clip(CRYSTAL_SSS, 0, 1) * 190.0)
    blue = np.where(crystal > 0.5, sss, porosity)

    # 255 is "no emission", so the stone stays parked there and only the
    # crystal comes down off it. Anything that would round to zero is put BACK
    # at 255 rather than left at 0: the two mean the same thing to a shader, but
    # a transparent pixel is fair game for the atlas mipmapper, which is free to
    # throw away the colour underneath it — and the colour underneath it here is
    # the smoothness.
    emission = np.clip(crystal * CRYSTAL_EMISSION, 0, 1) * 255.0
    alpha = np.where(emission >= 1.0, np.clip(np.round(emission), 1, 254), 255.0)

    out = np.stack([
        np.clip(smooth, 0, 1) * 255.0,
        np.clip(f0, 0, 229 / 255.0) * 255.0,
        blue,
        alpha,
    ], axis=2)
    return Image.fromarray(np.uint8(np.round(out)), mode="RGBA"), crystal


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", help="write a contact sheet of the masks here")
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)
    masks = []
    for path in sorted(glob.glob(TEXTURES, recursive=True)):
        if path.endswith(("_s.png", "_n.png")):
            continue
        source = Image.open(path)
        spec, crystal = specular(source)
        out = path[:-4] + "_s.png"
        # optimize=False on purpose: see the note about alpha at the top
        spec.save(out, optimize=False)
        share = float((crystal > 0.5).mean())
        print("%-56s %3d%% amethyst" % (out.replace(root + "/", ""), round(share * 100)))
        masks.append((source, crystal))

    if args.preview:
        zoom = 96
        sheet = Image.new("RGB", (zoom * len(masks), zoom * 2), (40, 40, 40))
        for i, (source, crystal) in enumerate(masks):
            sheet.paste(source.convert("RGB").resize((zoom, zoom), Image.NEAREST), (i * zoom, 0))
            heat = np.stack([crystal, crystal * 0.35, crystal], axis=2) * 255
            sheet.paste(Image.fromarray(np.uint8(heat)).resize((zoom, zoom), Image.NEAREST),
                        (i * zoom, zoom))
        sheet.save(args.preview)
        print("preview:", args.preview)


# Why no _n.png. A normal map derived from a colour texture is a guess that the
# brighter pixels stick out, and on 16x16 pixel art aimed at looking flat that
# guess is wrong about as often as it is right — the result is a surface that
# ripples where the artist drew shading and lies flat where they drew a real
# edge. Normals are worth having, but they have to be drawn, not inferred;
# Blockbench will generate a starting point from the colour map.
if __name__ == "__main__":
    main()
