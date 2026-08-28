<img src="src/main/resources/logo.png" width="96" align="right" alt="">

# Quantum Entanglement

A NeoForge mod for Minecraft 1.21.1.

Old versions of Minecraft had a bug where an item could end up in two places at
once, and taking from one copy emptied the other. It was a duplication exploit
and it got fixed. This mod puts the same idea back deliberately, with the
duplication taken out.

Up to four item stacks can be tied to one shared supply. Take five iron from
any of them and all four are five lighter. Put ten back and all four have ten
more. There is exactly one pile of items; the stacks are windows onto it.

Two rules hold everywhere, without exception:

- **Nothing is ever duplicated.**
- **Nothing is ever lost.**

Everything else in the mod exists to keep those two true. A window dropped on
the ground turns back into ordinary items. A window pulled apart by a hopper
comes out as ordinary items. Take the last item and the link simply ends.

## Making one

You need a Quantum Knot. They are not craftable — they turn up in Ancient City
chests, which means the Deep Dark is the gate to the whole mod.

The ritual is a 5×5 floor of amethyst blocks, a Resonator on each corner and a
Quantum Core in the middle. Nothing else may stand above the floor.

To create a network, put one stack on one Resonator, leave the others empty,
and set a Knot on the Core. The ritual runs, the Knot is consumed, and you get
back two windows onto that stack — one where you left it, one in an empty
Resonator.

To grow a network, lay out every window it already has, one per Resonator,
leave one Resonator empty, and run it again. Each ritual adds one window. Four
is the ceiling, because there are four Resonators.

The stack has to be stackable and undamageable. Tools, armour and anything with
durability are refused. If a rule is broken the ritual stops, your items stay
where they are, and the Knot is spent anyway — putting it on the Core is the
commitment, not the outcome.

The mod ships an in-game guide covering all of this. Hover any of its items or
blocks and press <kbd>W</kbd>.

## Living with a window

A window is an ordinary-looking stack with an ID in its tooltip. Hold
<kbd>Shift</kbd> for the full rules.

- It is safe in a shulker box.
- Hoppers, droppers and machines can pull from it, and what they get is
  ordinary items — a machine never ends up holding a window.
- A container holding one updates comparators when the shared supply changes
  somewhere else, which makes a pair of windows a wireless redstone line.

## Installing

Minecraft 1.21.1 and NeoForge 21.1.235 or newer. Drop the jar in `mods` on both
the client and the server; the same file works on either. Ponder, which the
guide is built on, is bundled inside — nothing else to install.

Multiplayer works. All item movement is decided by the server.

Shader packs are a known rough edge. The ritual beams are additive glowing
geometry, and a shader pack is free to draw that however it likes — some render
it pure white. The mod says so in chat once when it sees a pack running. Nothing
else is affected.

## Building

```
./gradlew build
```

Java 21. The jar lands in `build/libs`.

```
./gradlew runClient          # client with the mod loaded
./gradlew runGameTestServer  # the test suite, 108 in-world tests
```

The tests are the reason the two rules above can be stated flatly. They run a
real server, build real machines and count real items.

`tools/` holds the scripts that generate art and models rather than storing
results by hand — the logo, the specular maps, the core's item model, the
sounds. Each one regenerates its output from the source next to it, so nothing
drifts out of step with what it was made from.

## Admin commands

`/quantum networks` lists live networks and their contents, `/quantum remove
<id>` deletes one, `/quantum debug on` logs every pool operation. All require
permission level 2.

## Licence

MIT. See [LICENSE](LICENSE).
