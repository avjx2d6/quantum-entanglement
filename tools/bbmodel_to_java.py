#!/usr/bin/env python3
"""Convert a Blockbench .bbmodel into a Minecraft Java block model.

    python3 tools/bbmodel_to_java.py in.bbmodel out.json [--particle KEY]

Blockbench's own Java export is not usable for this project, for two reasons
that both cost us a debugging session already:

1. It addresses textures by their *id*, and Blockbench happily lets several
   textures share one. The core model has five different textures all called
   "deep"; on export every face using any of them collapses onto whichever won,
   and four textures silently vanish from the model. This reads the per-face
   texture INDEX out of the .bbmodel, which is unambiguous.

2. It writes element rotations verbatim, including ones Minecraft cannot
   parse. A cube stood on its corner is rotation [0, 45, 54.7356]; Blockbench
   exports {"x":0,"y":45,"z":54.7356} with no "axis" and no "angle", and
   BlockElement.Deserializer throws on the missing "axis" — which fails the
   WHOLE model, not just that cube. Those elements are reported and skipped
   here, to be drawn from code instead.

Texture paths are also rewritten from the author's working names to the ones
the repository uses; see NAMES.
"""

import json
import sys

# working name in Blockbench -> (key used in the model, resource path)
NAMES = {
    ("minecraft", "block", "polished_deepslate"): ("deep", "minecraft:block/polished_deepslate"),
    ("minecraft", "block", "deepslate_tiles"): ("tiles", "minecraft:block/deepslate_tiles"),
    ("minecraft", "block", "amethyst_block"): ("amet", "minecraft:block/amethyst_block"),
    # authored by us, but sitting in the minecraft namespace in Blockbench
    ("minecraft", "block", "eye"): ("eye", "quantumitems:block/observer_eye"),
    ("quantumitems", "block", "polished_deepslate_s"): ("side", "quantumitems:block/quantum_core_side"),
    ("quantumitems", "block", "polished_deepslate_u"): ("top", "quantumitems:block/quantum_core_top"),
    ("quantumitems", "block", "polished_deepslate_d"): ("bottom", "quantumitems:block/quantum_core_bottom"),
    ("quantumitems", "block", "polished_deepslate_e"): ("vein", "quantumitems:block/quantum_core_vein"),
    ("quantumitems", "block", "deepslate_tiles_c"): ("column", "quantumitems:block/quantum_core_column"),
    ("quantumitems", "block", "res_sd"): ("rail", "quantumitems:block/resonator_side"),
    ("quantumitems", "block", "res_up"): ("tray", "quantumitems:block/resonator_top"),
}

LEGAL_ANGLES = (-45.0, -22.5, 0.0, 22.5, 45.0)
AXES = ("x", "y", "z")


def texture_key(entry):
    name = entry.get("name", "").removesuffix(".png")
    ident = (entry.get("namespace") or "minecraft", entry.get("folder", "block"), name)
    if ident not in NAMES:
        raise SystemExit("unknown texture %s:%s/%s — add it to NAMES" % ident)
    return NAMES[ident]


def convert_rotation(element):
    """Java takes one axis and one of five angles. Anything else cannot load."""
    rot = element.get("rotation")
    if not rot or not any(rot):
        return None, None
    turning = [i for i, v in enumerate(rot) if v]
    if len(turning) > 1:
        return None, "turns on %d axes at once" % len(turning)
    axis = turning[0]
    angle = float(rot[axis])
    if angle not in LEGAL_ANGLES:
        return None, "angle %g is not a multiple of 22.5" % angle
    out = {"angle": angle, "axis": AXES[axis],
           "origin": element.get("origin", [8, 8, 8])}
    if element.get("rescale"):
        out["rescale"] = True
    return out, None


def convert(source, particle_key=None):
    model = json.load(open(source))
    keys, paths = [], {}
    for entry in model["textures"]:
        key, path = texture_key(entry)
        keys.append(key)
        paths[key] = path

    elements, skipped = [], []
    for element in model["elements"]:
        if element.get("type", "cube") != "cube":
            skipped.append((element.get("name", "?"), "not a cube"))
            continue
        rotation, problem = convert_rotation(element)
        if problem:
            skipped.append((element.get("name", "?"), problem))
            continue

        faces = {}
        for side, face in element.get("faces", {}).items():
            index = face.get("texture")
            if index is None:
                continue                      # a face with no texture is not drawn
            entry = {"uv": face["uv"], "texture": "#" + keys[index]}
            if face.get("rotation"):
                entry["rotation"] = face["rotation"]
            if face.get("tintindex", -1) >= 0:
                entry["tintindex"] = face["tintindex"]
            faces[side] = entry
        if not faces:
            skipped.append((element.get("name", "?"), "no textured faces"))
            continue

        out = {"from": element["from"], "to": element["to"], "faces": faces}
        if element.get("name"):
            out["name"] = element["name"]
        if rotation:
            out["rotation"] = rotation
        if element.get("shade") is False:
            out["shade"] = False
        elements.append(out)

    used = {f["texture"][1:] for e in elements for f in e["faces"].values()}
    textures = {k: v for k, v in paths.items() if k in used}
    if particle_key:
        textures["particle"] = paths[particle_key]
    return {"parent": "minecraft:block/block",
            "textures": dict(sorted(textures.items())),
            "elements": elements}, skipped


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    particle = next((a.split("=", 1)[1] for a in sys.argv[1:]
                     if a.startswith("--particle=")), None)
    if len(args) != 2:
        raise SystemExit(__doc__)
    model, skipped = convert(args[0], particle)
    with open(args[1], "w") as handle:
        json.dump(model, handle, indent=2)
        handle.write("\n")
    print("%s -> %s: %d elements, %d textures"
          % (args[0], args[1], len(model["elements"]), len(model["textures"])))
    for name, why in skipped:
        print("  SKIPPED %-10s %s" % (name, why))


if __name__ == "__main__":
    main()
