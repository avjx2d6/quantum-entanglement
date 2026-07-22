# Quantum Entanglement

*The old "shadow item" dupe bug, rebuilt as a real mechanic — without the dupe.*

For **NeoForge 1.21.1**.

---

## What it is

Long ago, a vanilla bug let two item stacks quietly share the same object in
memory: touch one, the other changed. Quantum Entanglement takes that idea and
makes it a deliberate, dupe-proof mechanic.

Several item stacks — **windows** — can belong to one **network** and share a
single **pool** of items. Every window always shows the whole pool. Take items
from one and they leave all of them, across any distance, any inventory, any
player.

One iron rule holds everywhere: **items are never duplicated and never lost.**
The link itself is fragile on purpose — mishandle a window and it collapses
back into ordinary items, but the items are always accounted for.

## How it plays

- **Entangle up to four windows** that share one pool. Store them anywhere —
  chests at opposite ends of the world, other players' inventories, a shulker
  box in your pocket.
- **It's an artifact, not logistics.** Hoppers, pipes and machines can only
  ever pull *ordinary* items out of a window — they can never hold the link
  itself. Take the last item through automation and the network ends honestly.
- **Fragility is a feature.** Rename or enchant a window, or drop it on the
  ground, and the link collapses: the whole pool spills out as plain items,
  right there. Nothing is gained, nothing is lost.
- **The ritual.** Networks are made at a multiblock ritual circle — a 5×5
  amethyst floor, four resonators, and a two-tall Quantum Core — fed by a rare
  **Quantum Shard** found deep in Ancient Cities. Lay out your stacks, drop the
  shard, and watch the circle work. A running ritual even siphons a little
  experience from anyone standing near.
- **A built-in guide.** Every mod item carries a **Ponder** scene — the ritual,
  step by step, right in-game. No wiki needed.
- **Advancements** for finding your first shard, building the circle, and
  filling a network to its cap.

## Dependencies

**None.** [Ponder](https://github.com/Creators-of-Create/Ponder) and
[Flywheel](https://github.com/Engine-Room/Flywheel) are bundled inside the jar,
so the in-game guide works out of the box. Create is *not* required.

Already running Create? No problem — the shared libraries are de-duplicated by
version, so only one copy loads.

## Install

Drop the jar into your `mods/` folder. That's it.

## Under the hood

The pool of every network is the single source of truth, kept in server
save-data; windows are dumb carriers of a `(network, member)` tag. The server
writes the live count into each window as the pool changes, so reads stay
completely vanilla. Every count change funnels through a handful of `ItemStack`
mix-ins, which is why hoppers, shulkers, crafting, furnaces, death drops and
modded transport all obey the same rules with no per-block code. Duplication is
impossible by construction: every extraction is bounded by the pool.

The mod ships with 100+ automated GameTests covering the entanglement rules and
the ritual.

## License

[MIT](LICENSE). Bundled Ponder / Flywheel / Catnip are MIT, © their authors.

## Credits

Design and direction by **avjx2d6**. Implementation was done with heavy AI
assistance (Anthropic's Claude), driven test-first.
