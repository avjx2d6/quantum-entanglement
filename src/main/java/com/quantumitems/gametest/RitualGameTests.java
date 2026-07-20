package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.block.QuantumCoreBlockEntity;
import com.quantumitems.block.ResonatorBlockEntity;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The ritual circle: 5×5 amethyst floor, resonators on the corners, the core
 * in the center. Lay stacks out first, commit with a shard — it burns whether
 * the ritual succeeds or fails, and every failure leaves the laid-out stacks
 * exactly as they were.
 */
@PrefixGameTestTemplate(false)
public class RitualGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(RitualGameTests.class);
        }
    }

    private static final BlockPos CORE = new BlockPos(3, 2, 3);
    private static final BlockPos[] RESONATORS = {
            new BlockPos(1, 2, 1), new BlockPos(5, 2, 1),
            new BlockPos(1, 2, 5), new BlockPos(5, 2, 5)};
    /** A successful ritual applies at the burst; a doomed one cancels at the verdict. */
    private static final int APPLY_TICKS = QuantumCoreBlockEntity.ticksUntilApply() + 4;
    private static final int CANCEL_TICKS = QuantumCoreBlockEntity.ticksUntilCancel() + 4;

    private static void buildCircle(GameTestHelper helper) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(CORE.offset(dx, -1, dz), Blocks.AMETHYST_BLOCK);
            }
        }
        for (BlockPos pos : RESONATORS) {
            helper.setBlock(pos, ModRegistry.RESONATOR.get());
        }
        placeCore(helper, CORE);
    }

    /** The core is two blocks tall (door pattern) — tests place both halves explicitly. */
    private static void placeCore(GameTestHelper helper, BlockPos lowerPos) {
        helper.setBlock(lowerPos, ModRegistry.QUANTUM_CORE.get().defaultBlockState());
        helper.setBlock(lowerPos.above(), ModRegistry.QUANTUM_CORE.get().defaultBlockState()
                .setValue(com.quantumitems.block.QuantumCoreBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
    }

    private static QuantumCoreBlockEntity core(GameTestHelper helper) {
        return (QuantumCoreBlockEntity) helper.getBlockEntity(CORE);
    }

    private static ResonatorBlockEntity resonator(GameTestHelper helper, int index) {
        return (ResonatorBlockEntity) helper.getBlockEntity(RESONATORS[index]);
    }

    private static ItemStack makeWindow(GameTestHelper helper, int networkId, int memberId, ItemStack template) {
        ItemStack window = template.copy();
        window.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(networkId, memberId));
        QuantumEngine.onServerThread().adopt(window);
        return window;
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void ritualCreatesNetworkFromPlainStack(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 20));
        if (!core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()))) {
            helper.fail("Ritual refused to start on a valid structure");
        }
        helper.runAfterDelay(APPLY_TICKS, () -> {
            ItemStack a = resonator(helper, 0).getItem(0);
            ItemStack b = findSecondWindow(helper);
            QuantumLinkData linkA = a.get(ModRegistry.QUANTUM_LINK.get());
            QuantumLinkData linkB = b.get(ModRegistry.QUANTUM_LINK.get());
            if (linkA == null || linkB == null || linkA.networkId() != linkB.networkId()) {
                helper.fail("Expected two windows of one network, got " + a + " / " + b);
            }
            QuantumNetworks.Network network = QuantumNetworks.get(helper.getLevel().getServer())
                    .network(linkA.networkId());
            if (network == null || network.pool != 20 || a.getCount() != 20 || b.getCount() != 20) {
                helper.fail("Pool/count mismatch after creation");
            }
            if (!core(helper).displayedShard().isEmpty()) {
                helper.fail("Shard was not consumed on success");
            }
            helper.succeed();
        });
    }

    private static ItemStack findSecondWindow(GameTestHelper helper) {
        for (int i = 1; i < RESONATORS.length; i++) {
            ItemStack stack = resonator(helper, i).getItem(0);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void ritualBurnsShardOnEmptyCircle(GameTestHelper helper) {
        buildCircle(helper);
        if (!core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()))) {
            helper.fail("Commitment is commitment: the shard goes in even over an empty circle");
        }
        helper.runAfterDelay(CANCEL_TICKS, () -> {
            for (int i = 0; i < RESONATORS.length; i++) {
                if (!resonator(helper, i).isEmpty()) {
                    helper.fail("Failed ritual must leave resonators untouched");
                }
            }
            if (core(helper).phase() != QuantumCoreBlockEntity.Phase.FAILURE) {
                helper.fail("Expected FAILURE phase, got " + core(helper).phase());
            }
            if (!core(helper).displayedShard().isEmpty()) {
                helper.fail("Shard must burn on failure too — the rules are taught, not refunded");
            }
            helper.succeed();
        });
    }

    /** On an unfinished machine the shard just lies on the core, inert and retrievable. */
    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void shardLiesInertOnUnfinishedCore(GameTestHelper helper) {
        placeCore(helper, CORE); // both halves, but no floor and no resonators
        ItemStack held = new ItemStack(ModRegistry.QUANTUM_SHARD.get(), 3);
        if (!core(helper).placeShard(held)) {
            helper.fail("Shard must lie on the core like on a resonator");
        }
        if (held.getCount() != 2) {
            helper.fail("Exactly one shard goes onto the core, hand has " + held.getCount());
        }
        if (core(helper).phase() != QuantumCoreBlockEntity.Phase.IDLE) {
            helper.fail("No ritual may start without a structure, phase " + core(helper).phase());
        }
        if (core(helper).displayedShard().isEmpty()) {
            helper.fail("The inert shard must be visible on the core");
        }
        ItemStack takenBack = core(helper).takeShard();
        if (!takenBack.is(ModRegistry.QUANTUM_SHARD.get()) || takenBack.getCount() != 1) {
            helper.fail("Empty-hand take must return the inert shard, got " + takenBack);
        }
        if (!core(helper).displayedShard().isEmpty()) {
            helper.fail("Core must be empty after the take");
        }
        helper.succeed();
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void ritualExpandsNetworkWhenAllWindowsPresent(GameTestHelper helper) {
        buildCircle(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        ItemStack plain = new ItemStack(Items.BREAD, 12);
        int id = networks.createNetwork(plain);
        resonator(helper, 0).setItem(0, makeWindow(helper, id, 1, plain));
        resonator(helper, 1).setItem(0, makeWindow(helper, id, 2, plain));
        if (!core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()))) {
            helper.fail("Ritual refused to start");
        }
        helper.runAfterDelay(APPLY_TICKS, () -> {
            QuantumNetworks.Network network = networks.network(id);
            if (network == null || network.aliveMembers.size() != 3) {
                helper.fail("Expected 3 members after expansion, network=" + network);
            }
            if (network.pool != 12) {
                helper.fail("Expansion must not change the pool");
            }
            int windows = 0;
            for (int i = 0; i < RESONATORS.length; i++) {
                ItemStack stack = resonator(helper, i).getItem(0);
                QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
                if (link != null && link.networkId() == id) {
                    windows++;
                    if (stack.getCount() != 12) {
                        helper.fail("Window count diverged from pool");
                    }
                }
            }
            if (windows != 3) {
                helper.fail("Expected 3 windows on the table, got " + windows);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void ritualFailsWhenAWindowIsMissing(GameTestHelper helper) {
        buildCircle(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        ItemStack plain = new ItemStack(Items.BREAD, 12);
        int id = networks.createNetwork(plain);
        ItemStack window1 = makeWindow(helper, id, 1, plain);
        ItemStack window2 = makeWindow(helper, id, 2, plain); // stays "elsewhere in the world"
        resonator(helper, 0).setItem(0, window1);
        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(CANCEL_TICKS, () -> {
            QuantumNetworks.Network network = networks.network(id);
            if (network == null || network.aliveMembers.size() != 2) {
                helper.fail("Recoherence demands every window on the table; members=" +
                        (network == null ? "gone" : network.aliveMembers));
            }
            ItemStack still = resonator(helper, 0).getItem(0);
            QuantumLinkData link = still.get(ModRegistry.QUANTUM_LINK.get());
            if (link == null || link.networkId() != id || link.memberId() != 1) {
                helper.fail("Failed ritual must leave the laid-out window untouched");
            }
            if (window2.getCount() != 12) {
                helper.fail("Remote window damaged by a failed ritual");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void ritualFailsOnTwoPlainStacks(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 5));
        resonator(helper, 1).setItem(0, new ItemStack(Items.APPLE, 7));
        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(CANCEL_TICKS, () -> {
            ItemStack a = resonator(helper, 0).getItem(0);
            ItemStack b = resonator(helper, 1).getItem(0);
            if (a.has(ModRegistry.QUANTUM_LINK.get()) || b.has(ModRegistry.QUANTUM_LINK.get())) {
                helper.fail("Ambiguous input must fail, not guess");
            }
            if (a.getCount() != 5 || b.getCount() != 7) {
                helper.fail("Failed ritual altered laid-out stacks");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void ritualRefusesDamageableItems(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.IRON_PICKAXE));
        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(CANCEL_TICKS, () -> {
            ItemStack stack = resonator(helper, 0).getItem(0);
            if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
                helper.fail("Damageable item was entangled — first tool hit would collapse it");
            }
            if (core(helper).phase() != QuantumCoreBlockEntity.Phase.FAILURE) {
                helper.fail("Expected FAILURE, got " + core(helper).phase());
            }
            helper.succeed();
        });
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void circleIsLockedDuringRitual(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 20));
        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(10, () -> {
            if (!resonator(helper, 0).isLockedByRitual() || !resonator(helper, 3).isLockedByRitual()) {
                helper.fail("Circle must be locked while the ritual runs");
            }
        });
        helper.runAfterDelay(APPLY_TICKS + QuantumCoreBlockEntity.SUCCESS_TICKS + 2, () -> {
            if (resonator(helper, 0).isLockedByRitual()) {
                helper.fail("Circle still locked after the ritual ended");
            }
            helper.succeed();
        });
    }

    /** A full network occupies all four resonators — no vacancy, the cap is visible. */
    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void fullNetworkCannotExpand(GameTestHelper helper) {
        buildCircle(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        ItemStack plain = new ItemStack(Items.BREAD, 8);
        int id = networks.createNetwork(plain);
        networks.addMember(id);
        networks.addMember(id);
        for (int i = 0; i < 4; i++) {
            resonator(helper, i).setItem(0, makeWindow(helper, id, i + 1, plain));
        }
        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(CANCEL_TICKS, () -> {
            QuantumNetworks.Network network = networks.network(id);
            if (network == null || network.aliveMembers.size() != 4) {
                helper.fail("Cap of four members violated: " + (network == null ? "gone" : network.aliveMembers));
            }
            if (core(helper).phase() != QuantumCoreBlockEntity.Phase.FAILURE) {
                helper.fail("Expected FAILURE (no vacant resonator), got " + core(helper).phase());
            }
            helper.succeed();
        });
    }

    /**
     * Playtest repro: create a network on the circle, TAKE one window by the
     * hand exchange, throw it on the ground. Rule 1 cashes the pool out into
     * the dropped stack — and the sibling windows on the resonators must be
     * wiped SERVER-side immediately (the phantom-pedestal bug).
     */
    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 450)
    public void droppedWindowWipesSiblingsOnResonators(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 20));
        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(APPLY_TICKS, () -> {
            // the exchange take, exactly as ResonatorBlock does it
            ItemStack taken = resonator(helper, 0).removeItemNoUpdate(0);
            resonator(helper, 0).setChanged();
            if (!taken.has(ModRegistry.QUANTUM_LINK.get())) {
                helper.fail("Expected to take a window, got " + taken);
            }
            // thrown on the ground: Rule 1 must cash out and wipe the siblings
            net.minecraft.core.BlockPos dropAt = helper.absolutePos(new BlockPos(3, 3, 1));
            helper.getLevel().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    helper.getLevel(), dropAt.getX() + 0.5, dropAt.getY(), dropAt.getZ() + 0.5, taken));
        });
        helper.runAfterDelay(APPLY_TICKS + 10, () -> {
            for (int i = 1; i < RESONATORS.length; i++) {
                ItemStack left = resonator(helper, i).getItem(0);
                if (!left.isEmpty()) {
                    helper.fail("Sibling window must be wiped server-side, resonator " + i
                            + " still holds " + left + " (link="
                            + left.get(ModRegistry.QUANTUM_LINK.get()) + ")");
                }
            }
            helper.succeed();
        });
    }

    /**
     * The phantom-pedestal regression: canonical refs legitimately drift to
     * copies (last-touch-wins). If the canonical of a pedestal window points
     * elsewhere when the network collapses, the wipe used to hit the copy
     * and leave the REAL stack on the resonator as a live-looking husk. The
     * holder-scan wipe must clear the pedestal by link, not by identity.
     */
    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void collapseClearsPedestalEvenWithStolenCanonical(GameTestHelper helper) {
        buildCircle(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, 12);
        int id = networks.createNetwork(plain);
        ItemStack windowA = makeWindow(helper, id, 1, plain);
        ItemStack windowB = makeWindow(helper, id, 2, plain);
        resonator(helper, 1).setItem(0, windowB);
        // canonical theft: a stray copy of m2 becomes the canonical instance
        engine.adopt(windowB.copy());

        engine.cashOutToPlain(windowA); // network ends; m2 must die IN the pedestal

        ItemStack left = resonator(helper, 1).getItem(0);
        if (!left.isEmpty()) {
            helper.fail("Pedestal must be cleared by the holder-scan wipe, still holds " + left);
        }
        if (windowA.has(ModRegistry.QUANTUM_LINK.get()) || windowA.getCount() != 12) {
            helper.fail("Cash-out target must be plain x12, got " + windowA);
        }
        helper.succeed();
    }

    /** A foreign block inside the circle invalidates the structure (author's rule). */
    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void foreignBlockInsideCircleInvalidatesStructure(GameTestHelper helper) {
        buildCircle(helper);
        helper.setBlock(CORE.offset(1, 0, 1), Blocks.STONE); // clutter inside the circle
        ItemStack held = new ItemStack(ModRegistry.QUANTUM_SHARD.get());
        core(helper).placeShard(held);
        if (core(helper).phase() != QuantumCoreBlockEntity.Phase.IDLE) {
            helper.fail("Cluttered circle must not launch, phase " + core(helper).phase());
        }
        helper.setBlock(CORE.offset(1, 0, 1), Blocks.AIR);
        ItemStack second = new ItemStack(ModRegistry.QUANTUM_SHARD.get());
        core(helper).takeShard();
        core(helper).placeShard(second);
        if (core(helper).phase() == QuantumCoreBlockEntity.Phase.IDLE) {
            helper.fail("Clean circle must launch again");
        }
        helper.succeed();
    }
}
