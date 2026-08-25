package com.quantumitems.client.ponder;

import com.quantumitems.ModRegistry;
import com.quantumitems.block.QuantumCoreBlock;
import com.quantumitems.block.QuantumCoreBlockEntity;
import com.quantumitems.block.ResonatorBlockEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Storyboards for the Quantum Entanglement guide. Each static method is one
 * scene played over a schematic in {@code assets/quantumitems/ponder/}.
 *
 * Items are shown with the mod's own renderers: laid on a Resonator via
 * {@link ResonatorBlockEntity#layDown}, and a shard parked in the Core frame
 * by writing its "shard" NBT (the Core BER draws {@code displayedShard()}).
 * The ritual itself is staged with Ponder glow + particles rather than the
 * real (server-only) phase machine, for control over timing.
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

        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(80)
                .text("The ritual circle is built on a 5x5 floor of Amethyst Blocks")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(2, 0, 2))
                .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().position(FIRST_CORNER), Direction.DOWN);
        scene.idle(6);
        scene.overlay().showOutlineWithText(util.select().position(FIRST_CORNER), 80)
                .text("Place a Resonator on each of the four corners")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(FIRST_CORNER))
                .placeNearTarget();
        scene.idle(90);

        Selection otherCorners = util.select().position(4, 1, 0)
                .add(util.select().position(0, 1, 4))
                .add(util.select().position(4, 1, 4));
        scene.world().showSection(otherCorners, Direction.DOWN);
        scene.idle(20);
        scene.overlay().showText(70)
                .text("Each Resonator holds a single stack of items")
                .pointAt(util.vector().topOf(new BlockPos(4, 1, 0)))
                .placeNearTarget();
        scene.idle(80);

        Selection core = util.select().fromTo(2, 1, 2, 2, 2, 2);
        scene.world().showSection(core, Direction.DOWN);
        scene.idle(8);
        scene.overlay().showOutlineWithText(core, 90)
                .text("The Quantum Core stands at the center of the circle")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(CORE_UPPER))
                .placeNearTarget();
        scene.idle(100);

        // Cleanliness rule — one segment: the stray block drops in and its
        // warning show together (a bare setBlock on an unshown position never
        // renders, so setBlock + showSection make it actually appear).
        BlockPos intruder = new BlockPos(1, 1, 2);
        scene.world().setBlock(intruder, Blocks.COBBLESTONE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(intruder), Direction.DOWN);
        scene.overlay().showOutline(PonderPalette.RED, intruder, util.select().position(intruder), 80);
        scene.overlay().showText(85)
                .colored(PonderPalette.RED)
                .text("The space above the floor has to stay clear of other blocks")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(intruder))
                .placeNearTarget();
        scene.idle(95);
        scene.world().destroyBlock(intruder);
        scene.idle(20);

        scene.overlay().showText(80)
                .text("Once the circle is complete, it is ready to entangle items")
                .pointAt(util.vector().topOf(CORE_UPPER));
        scene.idle(80);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 2 — creating a network: one stack becomes two.
    // =====================================================================
    public static void createNetwork(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("create", "Creating a Network");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.9f);
        scene.showBasePlate();
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);

        BlockPos inputRes = RESONATORS[0];
        ItemStack stack = new ItemStack(Items.GOLD_INGOT, 16);

        scene.world().modifyBlockEntity(inputRes, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.idle(10);
        scene.overlay().showOutlineWithText(util.select().position(inputRes), 80)
                .text("Lay a single stack of items on just one Resonator")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(inputRes))
                .placeNearTarget();
        scene.idle(90);

        parkShard(scene, util);
        scene.idle(10);
        scene.overlay().showText(80)
                .text("Then place a Quantum Knot on the Core to start the ritual")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(CORE_UPPER).add(0, 0.3, 0))
                .placeNearTarget();
        scene.idle(90);

        ritualFlash(scene, util);
        clearShard(scene, util);

        BlockPos copyRes = RESONATORS[3];
        scene.world().modifyBlockEntity(copyRes, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.effects().indicateSuccess(copyRes);
        scene.idle(10);
        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .text("One Knot turns that stack into two entangled windows")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(copyRes))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .text("The Knot is spent either way, success or failure")
                .pointAt(util.vector().centerOf(CORE_UPPER).add(0, 0.3, 0));
        scene.idle(100);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 3 — growing a network: lay out all its stacks, add one more.
    // =====================================================================
    public static void expandNetwork(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("expand", "Growing a Network");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.9f);
        scene.showBasePlate();
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);

        ItemStack stack = new ItemStack(Items.GOLD_INGOT, 16);
        BlockPos a = RESONATORS[0];
        BlockPos b = RESONATORS[3];
        scene.world().modifyBlockEntity(a, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.world().modifyBlockEntity(b, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.idle(10);
        Selection existing = util.select().position(a).add(util.select().position(b));
        scene.overlay().showOutlineWithText(existing, 90)
                .text("To grow a network, first lay out every stack it already has")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(a))
                .placeNearTarget();
        scene.idle(100);

        parkShard(scene, util);
        scene.idle(10);
        scene.overlay().showText(70)
                .text("Then run the ritual again with another Knot")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(CORE_UPPER).add(0, 0.3, 0))
                .placeNearTarget();
        scene.idle(80);

        ritualFlash(scene, util);
        clearShard(scene, util);

        BlockPos c = RESONATORS[1];
        scene.world().modifyBlockEntity(c, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.effects().indicateSuccess(c);
        scene.idle(10);
        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .text("Each ritual entangles one more stack into the network")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(c))
                .placeNearTarget();
        scene.idle(100);

        BlockPos d = RESONATORS[2];
        scene.world().modifyBlockEntity(d, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.idle(10);
        scene.overlay().showText(95)
                .text("Growing needs an empty Resonator, so four stacks is the limit")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(d))
                .placeNearTarget();
        scene.idle(105);
        scene.markAsFinished();
    }

    // =====================================================================
    // Scene 4 — when the ritual fails.
    // =====================================================================
    public static void failedRitual(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("failure", "When the Ritual Fails");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.9f);
        scene.showBasePlate();
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);

        // A bad setup: two different stacks (creation needs exactly one input).
        BlockPos a = RESONATORS[0];
        BlockPos b = RESONATORS[1];
        scene.world().modifyBlockEntity(a, ResonatorBlockEntity.class,
                be -> be.layDown(new ItemStack(Items.GOLD_INGOT, 16), Direction.SOUTH));
        scene.world().modifyBlockEntity(b, ResonatorBlockEntity.class,
                be -> be.layDown(new ItemStack(Items.IRON_INGOT, 16), Direction.SOUTH));
        scene.idle(10);
        scene.overlay().showOutlineWithText(util.select().position(a).add(util.select().position(b)), 95)
                .text("A new network takes exactly ONE stack, and it must stack and not wear out")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(a))
                .placeNearTarget();
        scene.idle(105);

        // The empty corner, not the core: this line is about where the new
        // window appears, and it used to point at the machine instead.
        BlockPos spare = RESONATORS[3];
        scene.overlay().showOutlineWithText(util.select().position(spare), 90)
                .colored(PonderPalette.BLUE)
                .text("One Resonator must stay empty - that is where the new window appears")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(spare))
                .placeNearTarget();
        scene.idle(100);

        parkShard(scene, util);
        scene.idle(30);
        failFlash(scene, util);
        clearShard(scene, util);
        scene.idle(10);
        scene.overlay().showText(95)
                .colored(PonderPalette.RED)
                .text("Break a rule and the circle collapses - the Knot is spent, the items untouched")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(CORE_UPPER).add(0, 0.3, 0));
        scene.idle(105);

        scene.overlay().showText(100)
                .text("Growing a network needs every one of its windows laid out, and nothing else")
                .pointAt(util.vector().topOf(b))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }

    // ---------------------------------------------------------------------
    // Shared helpers.
    // ---------------------------------------------------------------------

    /** Park a shard in the Core frame by writing its NBT (real BER draws it). */
    private static void parkShard(SceneBuilder scene, SceneBuildingUtil util) {
        scene.world().modifyBlockEntityNBT(util.select().position(CORE_LOWER),
                QuantumCoreBlockEntity.class,
                nbt -> nbt.put("shard", new ItemStack(ModRegistry.QUANTUM_SHARD.get())
                        .save(scene.world().getHolderLookupProvider())),
                true);
    }

    private static void clearShard(SceneBuilder scene, SceneBuildingUtil util) {
        scene.world().modifyBlockEntityNBT(util.select().position(CORE_LOWER),
                QuantumCoreBlockEntity.class, nbt -> nbt.remove("shard"), true);
    }

    /**
     * A short ritual beat: the Core brightens in steps, then a spark burst.
     * Only the upper block's glow is changed so the lower block-entity (which
     * holds the parked shard) is never rebuilt. Cosmetic only — the real
     * entanglement math never runs in a ponder world.
     */
    private static void ritualFlash(SceneBuilder scene, SceneBuildingUtil util) {
        Vec3 heart = util.vector().centerOf(CORE_UPPER);
        for (int g = 1; g <= 3; g++) {
            final int glow = g * 5; // 5 → 10 → 15 on the 0..15 light scale
            scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(QuantumCoreBlock.GLOW, glow), false);
            scene.idle(15);
        }
        scene.effects().emitParticles(heart, scene.effects().simpleParticleEmitter(
                ParticleTypes.END_ROD, util.vector().of(0, 0.12, 0)), 10f, 1);
        scene.effects().emitParticles(heart, scene.effects().simpleParticleEmitter(
                ParticleTypes.ELECTRIC_SPARK, util.vector().of(0, 0, 0)), 24f, 1);
        scene.idle(12);
        scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(QuantumCoreBlock.GLOW, 0), false);
    }

    /**
     * A failed ritual: a brief red flicker, then a puff of smoke as it dies.
     * No build-up, no success glow — the circle rejects the pattern.
     */
    private static void failFlash(SceneBuilder scene, SceneBuildingUtil util) {
        Vec3 heart = util.vector().centerOf(CORE_UPPER);
        scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(QuantumCoreBlock.GLOW, 6), false);
        scene.idle(10);
        scene.effects().createRedstoneParticles(CORE_UPPER, 0xE23A3A, 20);
        scene.effects().emitParticles(heart, scene.effects().simpleParticleEmitter(
                ParticleTypes.SMOKE, util.vector().of(0, 0.08, 0)), 16f, 1);
        scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(QuantumCoreBlock.GLOW, 0), false);
        scene.idle(12);
    }
}
