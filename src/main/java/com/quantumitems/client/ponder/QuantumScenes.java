package com.quantumitems.client.ponder;

import com.quantumitems.ModRegistry;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Storyboards for the Quantum Entanglement guide. Each static method is one
 * scene played over a schematic in {@code assets/quantumitems/ponder/}.
 *
 * Visuals are staged with Ponder instructions (show sections, outlines, click
 * gestures, lines, particles) rather than the real block-entity logic: the
 * ponder world is a client-side facade, so the theatre is driven by hand for
 * control over timing and framing.
 *
 * Text is plain and instructional, matching Create's own scenes.
 *
 * Schematic {@code ritual_circle} (5x3x5):
 *   y=0  5x5 amethyst floor
 *   y=1  resonators at corners (0,1,0)(4,1,0)(0,1,4)(4,1,4); core lower (2,1,2)
 *   y=2  core upper (2,2,2)
 */
public class QuantumScenes {

    private static final BlockPos CORE_LOWER = new BlockPos(2, 1, 2);
    private static final BlockPos CORE_UPPER = new BlockPos(2, 2, 2);
    private static final BlockPos FIRST_CORNER = new BlockPos(0, 1, 0);
    private static final BlockPos[] RESONATORS = {
            new BlockPos(0, 1, 0), new BlockPos(4, 1, 0),
            new BlockPos(0, 1, 4), new BlockPos(4, 1, 4),
    };

    // =====================================================================
    // Scene 1 — assembling the ritual circle.
    // =====================================================================
    public static void circleAssembly(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("circle", "Assembling the Ritual Circle");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.9f);

        // Floor rises into place.
        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(80)
                .text("The ritual circle is built on a 5x5 floor of Amethyst Blocks")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(2, 0, 2))
                .placeNearTarget();
        scene.idle(90);

        // First resonator, with a place-here gesture and an outline on the corner.
        scene.world().showSection(util.select().position(FIRST_CORNER), Direction.DOWN);
        scene.idle(6);
        scene.overlay().showControls(util.vector().centerOf(FIRST_CORNER).add(0, 0.4, 0), Pointing.DOWN, 40)
                .rightClick()
                .withItem(new ItemStack(ModRegistry.RESONATOR_ITEM.get()));
        scene.overlay().showOutlineWithText(util.select().position(FIRST_CORNER), 80)
                .text("Place a Resonator on each of the four corners")
                .attachKeyFrame()
                .pointAt(util.vector().blockSurface(FIRST_CORNER, Direction.WEST))
                .placeNearTarget();
        scene.idle(90);

        // The other three corners fill in.
        Selection otherCorners = util.select().position(4, 1, 0)
                .add(util.select().position(0, 1, 4))
                .add(util.select().position(4, 1, 4));
        scene.world().showSection(otherCorners, Direction.DOWN);
        scene.idle(20);
        scene.overlay().showText(70)
                .text("Each Resonator holds a single stack of items")
                .pointAt(util.vector().blockSurface(new BlockPos(4, 1, 4), Direction.EAST))
                .placeNearTarget();
        scene.idle(80);

        // Core rises in the center.
        Selection core = util.select().fromTo(2, 1, 2, 2, 2, 2);
        scene.world().showSection(core, Direction.DOWN);
        scene.idle(8);
        scene.overlay().showControls(util.vector().centerOf(CORE_LOWER).add(0, 0.4, 0), Pointing.DOWN, 40)
                .rightClick()
                .withItem(new ItemStack(ModRegistry.QUANTUM_CORE_ITEM.get()));
        scene.overlay().showOutlineWithText(core, 90)
                .text("The Quantum Core stands two blocks tall in the center")
                .attachKeyFrame()
                .pointAt(util.vector().blockSurface(CORE_UPPER, Direction.WEST))
                .placeNearTarget();
        scene.idle(100);

        // Cleanliness rule, shown: a stray block appears, is flagged, and removed.
        scene.addKeyframe();
        BlockPos intruder = new BlockPos(1, 1, 2);
        scene.world().setBlock(intruder, Blocks.COBBLESTONE.defaultBlockState(), true);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.RED, intruder, util.select().position(intruder), 70);
        scene.overlay().showText(80)
                .colored(PonderPalette.RED)
                .text("The space above the floor has to stay clear of other blocks")
                .attachKeyFrame()
                .pointAt(util.vector().blockSurface(intruder, Direction.WEST))
                .placeNearTarget();
        scene.idle(85);
        scene.world().destroyBlock(intruder);
        scene.idle(20);

        scene.overlay().showText(80)
                .text("Once the circle is complete, it is ready to entangle items")
                .pointAt(util.vector().topOf(CORE_UPPER));
        scene.idle(80);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 2 — running the ritual.
    // =====================================================================
    public static void ritual(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("ritual", "Entangling Item Stacks");
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
                .text("Lay a matching stack of items on all four Resonators")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(FIRST_CORNER))
                .placeNearTarget();
        scene.idle(90);

        // Drop the shard on the core.
        scene.world().createItemEntity(coreTop.add(0, 0.5, 0), util.vector().of(0, -0.1, 0),
                new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        scene.idle(15);
        scene.overlay().showText(80)
                .text("Then place a Quantum Shard on the Core to start the ritual")
                .attachKeyFrame()
                .pointAt(coreTop)
                .placeNearTarget();
        scene.idle(90);

        // Connecting lines reach out to each resonator.
        scene.overlay().showText(70)
                .text("The Core links to each Resonator, then checks the stacks")
                .attachKeyFrame()
                .pointAt(coreTop);
        for (BlockPos res : RESONATORS) {
            scene.overlay().showLine(PonderPalette.BLUE, coreTop,
                    util.vector().centerOf(res).add(0, 0.6, 0), 70);
            scene.idle(16);
        }
        scene.idle(40);

        // Verdict beam + crescendo.
        scene.overlay().showBigLine(PonderPalette.OUTPUT,
                util.vector().centerOf(RESONATORS[0]).add(0, 0.6, 0),
                coreTop.add(0, 1.2, 0), 90);
        scene.world().modifyBlock(CORE_LOWER, s -> s.setValue(
                com.quantumitems.block.QuantumCoreBlock.GLOW, 2), false);
        scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(
                com.quantumitems.block.QuantumCoreBlock.GLOW, 2), false);
        scene.effects().emitParticles(coreTop,
                scene.effects().simpleParticleEmitter(
                        net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                        util.vector().of(0, 0.05, 0)),
                6f, 30);
        scene.overlay().showText(80)
                .text("If the stacks all match, they become entangled")
                .attachKeyFrame()
                .pointAt(coreTop);
        scene.idle(90);

        scene.effects().indicateSuccess(CORE_LOWER);
        scene.overlay().showText(80)
                .colored(PonderPalette.GREEN)
                .text("Entangled stacks share one pool of items across any distance")
                .pointAt(coreTop);
        scene.idle(90);

        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .text("The Shard is used up whether the ritual succeeds or fails")
                .pointAt(coreTop);
        scene.idle(100);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 3 — one shared pool.
    // =====================================================================
    public static void sharedPool(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("shared_pool", "One Shared Pool");
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
                .text("Entangled stacks can be stored anywhere, even far apart")
                .attachKeyFrame()
                .pointAt(util.vector().blockSurface(left, Direction.UP))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showBigLine(PonderPalette.INPUT,
                util.vector().centerOf(left), util.vector().centerOf(right), 120);
        scene.overlay().showText(90)
                .text("They do not hold separate items - they share one pool")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(new BlockPos(4, 1, 0)));
        scene.idle(100);

        scene.overlay().showText(100)
                .colored(PonderPalette.GREEN)
                .text("Take items from one and they leave the others too, never copied")
                .pointAt(util.vector().blockSurface(right, Direction.UP))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }
}
