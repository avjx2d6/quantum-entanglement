# Blockbench sources

The models the block JSONs were exported from. Keep these: an exported Java
model cannot be turned back into one of these, because the export drops the
outliner groups and silently rewrites anything Minecraft's format cannot hold —
the observer's gem is a two-axis rotation that only survives here.

| file | exports to |
|---|---|
| `quantum_core_lower.bbmodel` | `models/block/quantum_core_lower.json` |
| `quantum_core_upper.bbmodel` | `models/block/quantum_core_upper.json` |
| `resonator.bbmodel` | `models/block/resonator.json` |

Do not use Blockbench's own Java export. It addresses textures by id and lets
several share one — the core has five all called `deep` — so on export every
face using any of them collapses onto whichever wins, and four textures vanish
from the model without a word. Use the converter instead:

    python3 tools/bbmodel_to_java.py art/resonator.bbmodel \
        src/main/resources/assets/quantumitems/models/block/resonator.json --particle=deep

It reads per-face texture indices, rewrites the working texture names to the
ones the mod ships, and reports any element it had to skip rather than emitting
something the game will refuse to load.

The core's item model is generated from the two halves afterwards:

    python3 tools/build_core_item_model.py
