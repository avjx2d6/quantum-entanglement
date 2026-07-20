package com.quantumitems.client.ponder;

import com.quantumitems.ModRegistry;
import com.quantumitems.block.QuantumCoreBlock;
import com.quantumitems.block.QuantumCoreBlockEntity;
import com.quantumitems.block.ResonatorBlockEntity;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.ParticleEmitter;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Storyboards for the Quantum Entanglement guide. Each static method is one
 * scene played over a schematic in {@code assets/quantumitems/ponder/}.
 *
 * Where possible the scenes drive the mod's own renderers — items laid on a
 * Resonator ({@link ResonatorBlockEntity#layDown}) and a shard parked in the
 * Core frame ({@link QuantumCoreBlockEntity#setDisplayShard}) — so the scene
 * looks exactly like the real block. The ritual itself is staged with Ponder
 * particles/glow rather than the real (server-only) phase machine, for control
 * over timing.
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

        // Cleanliness rule, shown: a stray block drops in, is flagged red, and
        // is cleared away. setBlock + showSection so it actually renders (a bare
        // setBlock on an unshown position leaves it invisible).
        scene.addKeyframe();
        BlockPos intruder = new BlockPos(1, 1, 2);
        scene.world().setBlock(intruder, Blocks.COBBLESTONE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(intruder), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.RED, intruder, util.select().position(intruder), 70);
        scene.overlay().showText(80)
                .colored(PonderPalette.RED)
                .text("The space above the floor has to stay clear of other blocks")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(intruder))
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

        parkShard(scene);
        scene.idle(8);
        scene.overlay().showText(80)
                .text("Then place a Quantum Shard on the Core to start the ritual")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(CORE_UPPER).add(0, 0.3, 0))
                .placeNearTarget();
        scene.idle(90);

        ritualFlash(scene, util, inputRes);
        clearShard(scene);

        BlockPos copyRes = RESONATORS[3];
        scene.world().modifyBlockEntity(copyRes, ResonatorBlockEntity.class,
                be -> be.layDown(stack.copy(), Direction.SOUTH));
        scene.effects().indicateSuccess(copyRes);
        scene.idle(10);
        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .text("One Shard turns that stack into two entangled stacks")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(copyRes))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .text("The Shard is used up whether the ritual succeeds or fails")
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

        parkShard(scene);
        scene.idle(8);
        scene.overlay().showText(70)
                .text("Then run the ritual again with another Shard")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(CORE_UPPER).add(0, 0.3, 0))
                .placeNearTarget();
        scene.idle(80);

        ritualFlash(scene, util, a);
        clearShard(scene);

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
    // Scene 4 — one shared pool across distant containers.
    // =====================================================================
    public static void sharedPool(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("shared_pool", "One Shared Pool");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos left = new BlockPos(0, 1, 2);
        BlockPos right = new BlockPos(4, 1, 2);
        scene.world().showSection(util.select().position(left), Direction.DOWN);
        scene.world().showSection(util.select().position(right), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(90)
                .text("Entangled stacks can sit far apart, in separate containers")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(left))
                .placeNearTarget();
        scene.idle(100);

        // A stack rests in each barrel; a link shows they are one pool.
        ItemStack pool = new ItemStack(Items.GOLD_INGOT, 16);
        Vec3 leftItem = util.vector().centerOf(left).add(0, 0.55, 0);
        Vec3 rightItem = util.vector().centerOf(right).add(0, 0.55, 0);
        ElementLink<EntityElement> leftStack =
                scene.world().createItemEntity(leftItem, util.vector().of(0, 0, 0), pool.copy());
        ElementLink<EntityElement> rightStack =
                scene.world().createItemEntity(rightItem, util.vector().of(0, 0, 0), pool.copy());
        scene.idle(10);
        scene.overlay().showLine(PonderPalette.INPUT, leftItem, rightItem, 90);
        scene.overlay().showText(90)
                .text("They are not two piles - they are one shared pool")
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(new BlockPos(2, 1, 2)).add(0, 0.6, 0));
        scene.idle(100);

        // Take from the left barrel; the right empties at the same instant.
        scene.overlay().showControls(leftItem, Pointing.DOWN, 40).rightClick();
        scene.idle(8);
        scene.world().modifyEntity(leftStack, Entity::discard);
        scene.world().modifyEntity(rightStack, Entity::discard);
        scene.effects().indicateSuccess(right);
        scene.world().createItemEntity(leftItem.add(0, 0.2, 0), util.vector().of(0, 0.25, 0.15), pool.copy());
        scene.idle(15);
        scene.overlay().showText(100)
                .colored(PonderPalette.GREEN)
                .text("Take items from one and they leave the other too, never copied")
                .attachKeyFrame()
                .pointAt(util.vector().topOf(right))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }

    // ---------------------------------------------------------------------
    // Shared helpers.
    // ---------------------------------------------------------------------

    /** Park a shard in the Core frame via the real renderer (no ritual). */
    private static void parkShard(SceneBuilder scene) {
        scene.world().modifyBlockEntity(CORE_LOWER, QuantumCoreBlockEntity.class,
                be -> be.setDisplayShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get())));
    }

    private static void clearShard(SceneBuilder scene) {
        scene.world().modifyBlockEntity(CORE_LOWER, QuantumCoreBlockEntity.class,
                be -> be.setDisplayShard(ItemStack.EMPTY));
    }

    /**
     * A short ritual beat: streams of energy rise from the input Resonator to
     * the Core, the Core glows brighter in steps, then a spark burst. Purely
     * cosmetic — the real entanglement math never runs in a ponder world.
     */
    private static void ritualFlash(SceneBuilder scene, SceneBuildingUtil util, BlockPos fromRes) {
        Vec3 heart = util.vector().centerOf(CORE_UPPER);
        Vec3 start = util.vector().centerOf(fromRes).add(0, 0.4, 0);
        Vec3 toHeart = heart.subtract(start);
        for (int g = 1; g <= 3; g++) {
            final int glow = g;
            scene.world().modifyBlock(CORE_LOWER, s -> s.setValue(QuantumCoreBlock.GLOW, glow), false);
            scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(QuantumCoreBlock.GLOW, glow), false);
            ParticleEmitter stream = scene.effects().simpleParticleEmitter(
                    ParticleTypes.ENCHANT, toHeart.scale(0.05));
            Vec3 step = start;
            for (int i = 0; i < 4; i++) {
                scene.effects().emitParticles(step, stream, 3f, 1);
                step = step.add(toHeart.scale(0.25));
            }
            scene.idle(15);
        }
        scene.effects().emitParticles(heart,
                scene.effects().simpleParticleEmitter(ParticleTypes.ELECTRIC_SPARK, util.vector().of(0, 0, 0)),
                30f, 1);
        scene.idle(12);
        scene.world().modifyBlock(CORE_LOWER, s -> s.setValue(QuantumCoreBlock.GLOW, 0), false);
        scene.world().modifyBlock(CORE_UPPER, s -> s.setValue(QuantumCoreBlock.GLOW, 0), false);
    }
}
