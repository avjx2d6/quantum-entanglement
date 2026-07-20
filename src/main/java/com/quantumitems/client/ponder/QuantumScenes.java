package com.quantumitems.client.ponder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Storyboards for the Quantum Entanglement guide. Each static method is one
 * {@link net.createmod.ponder.api.scene.PonderStoryBoard} — a single scene
 * played over a schematic in {@code assets/quantumitems/ponder/}.
 *
 * The visuals are driven entirely by Ponder instructions (showSection, lines,
 * particles, block swaps) rather than the real block-entity ritual logic: the
 * ponder world is a client-side facade, so we stage the theatre by hand for
 * full control over timing and framing.
 *
 * Schematic layout ({@code ritual_circle}, size 5×3×5):
 *   y=0  5×5 amethyst floor
 *   y=1  resonators at corners (0,1,0)(4,1,0)(0,1,4)(4,1,4); core lower (2,1,2)
 *   y=2  core upper (2,2,2)
 */
public class QuantumScenes {

    private static final BlockPos CORE_LOWER = new BlockPos(2, 1, 2);
    private static final BlockPos CORE_UPPER = new BlockPos(2, 2, 2);
    private static final BlockPos[] RESONATORS = {
            new BlockPos(0, 1, 0), new BlockPos(4, 1, 0),
            new BlockPos(0, 1, 4), new BlockPos(4, 1, 4),
    };

    // =====================================================================
    // Scene 1 — "The Circle": assembling the multiblock.
    // =====================================================================
    public static void circleAssembly(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("circle", "Building the Ritual Circle");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.9f);

        // Floor fades in first.
        scene.showBasePlate();
        scene.idle(10);
        scene.overlay().showText(70)
                .text("A five-by-five floor of Amethyst holds the ritual together")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(2, 0, 2))
                .placeNearTarget();
        scene.idle(80);

        // Resonators drop onto the corners.
        Selection resonators = util.select().position(0, 1, 0)
                .add(util.select().position(4, 1, 0))
                .add(util.select().position(0, 1, 4))
                .add(util.select().position(4, 1, 4));
        scene.world().showSection(resonators, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(80)
                .text("Four Resonators stand on the corners — pedestals that each hold one stack")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(0, 1, 0))
                .placeNearTarget();
        scene.idle(90);

        // The two-tall core rises in the middle.
        Selection core = util.select().position(CORE_LOWER).add(util.select().position(CORE_UPPER));
        scene.world().showSection(core, Direction.DOWN);
        scene.idle(20);
        scene.overlay().showText(90)
                .text("The Quantum Core, two blocks tall, presides at the centre")
                .attachKeyFrame()
                .pointAt(util.vector().blockSurface(CORE_UPPER, Direction.WEST))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Keep the circle clean: no stray blocks in the space above the floor")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(2, 1, 2));
        scene.idle(100);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 2 — "Entanglement": running the ritual, and how it can fail.
    // =====================================================================
    public static void ritual(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("ritual", "Entangling the Stacks");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.9f);
        scene.showBasePlate();
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);

        Vec3 coreTop = util.vector().centerOf(CORE_UPPER).add(0, 0.2, 0);

        // Lay a matching stack on each resonator.
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        for (BlockPos res : RESONATORS) {
            scene.world().createItemEntity(util.vector().centerOf(res).add(0, 0.6, 0),
                    util.vector().of(0, 0, 0), diamond.copy());
        }
        scene.idle(10);
        scene.overlay().showText(80)
                .text("Lay one stack on every resonator — all four must match")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(0, 1, 0))
                .placeNearTarget();
        scene.idle(90);

        // Drop the shard on the core; it fuels the rite.
        scene.world().createItemEntity(coreTop.add(0, 0.5, 0), util.vector().of(0, -0.1, 0),
                new ItemStack(com.quantumitems.ModRegistry.QUANTUM_SHARD.get()));
        scene.idle(15);
        scene.overlay().showText(80)
                .text("Place a Quantum Shard on the core to begin — it is consumed either way")
                .colored(PonderPalette.MEDIUM)
                .attachKeyFrame()
                .pointAt(coreTop)
                .placeNearTarget();
        scene.idle(90);

        // Connecting: a line reaches out to each resonator, one by one.
        scene.overlay().showText(70)
                .text("The core reaches out to each resonator in turn")
                .attachKeyFrame()
                .pointAt(coreTop);
        for (BlockPos res : RESONATORS) {
            scene.overlay().showLine(PonderPalette.BLUE, coreTop,
                    util.vector().centerOf(res).add(0, 0.6, 0), 70);
            scene.idle(18);
        }
        scene.idle(40);

        // Judgement: a golden output beam confirms a valid pattern.
        scene.overlay().showText(80)
                .text("If the pattern holds, a golden verdict names the winning corner")
                .colored(PonderPalette.OUTPUT)
                .attachKeyFrame()
                .pointAt(coreTop);
        scene.overlay().showBigLine(PonderPalette.OUTPUT,
                util.vector().centerOf(RESONATORS[0]).add(0, 0.6, 0),
                coreTop.add(0, 1.2, 0), 90);
        scene.idle(95);

        // Crescendo: glow climbs, sparks fly.
        scene.world().modifyBlock(CORE_LOWER, s -> s.setValue(
                com.quantumitems.block.QuantumCoreBlock.GLOW, 2), false);
        scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(
                com.quantumitems.block.QuantumCoreBlock.GLOW, 2), false);
        scene.effects().emitParticles(coreTop,
                scene.effects().simpleParticleEmitter(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                        util.vector().of(0, 0.05, 0)),
                6f, 30);
        scene.overlay().showText(80)
                .text("The circle builds to a crescendo, then the entanglement snaps into place")
                .attachKeyFrame()
                .pointAt(coreTop);
        scene.idle(90);

        scene.effects().indicateSuccess(CORE_LOWER);
        scene.overlay().showText(80)
                .text("Now those stacks are one pool, shared across any distance")
                .colored(PonderPalette.GREEN)
                .pointAt(coreTop);
        scene.idle(90);

        // Failure aside.
        scene.overlay().showText(90)
                .text("A broken pattern instead ends in a loud collapse — and the shard is still spent")
                .colored(PonderPalette.RED)
                .pointAt(coreTop);
        scene.idle(100);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 3 — "One For All": one pool through windows far apart.
    // =====================================================================
    public static void sharedPool(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("shared_pool", "One Pool, Any Distance");
        scene.configureBasePlate(0, 0, 1);
        scene.scaleSceneView(0.9f);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos left = new BlockPos(0, 1, 0);
        BlockPos right = new BlockPos(8, 1, 0);
        Selection chests = util.select().position(left).add(util.select().position(right));
        scene.world().showSection(chests, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(90)
                .text("Two entangled windows can sit anywhere — even in chests at opposite ends")
                .attachKeyFrame()
                .pointAt(util.vector().blockSurface(left, Direction.UP))
                .placeNearTarget();
        scene.idle(100);

        // Link the two with a bright line to show they are the same pool.
        scene.overlay().showBigLine(PonderPalette.INPUT,
                util.vector().centerOf(left), util.vector().centerOf(right), 120);
        scene.overlay().showText(90)
                .text("They do not each hold items — they are two views of a single shared pool")
                .colored(PonderPalette.INPUT)
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(new BlockPos(4, 1, 0)));
        scene.idle(100);

        scene.overlay().showText(100)
                .text("Take from one and the other empties too. Nothing is ever duplicated")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(right, Direction.UP))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }
}
