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
    private static final int VERDICT_TICKS = QuantumCoreBlockEntity.CHARGING_TICKS
            + QuantumCoreBlockEntity.JUDGEMENT_TICKS + 2;

    private static void buildCircle(GameTestHelper helper) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(CORE.offset(dx, -1, dz), Blocks.AMETHYST_BLOCK);
            }
        }
        for (BlockPos pos : RESONATORS) {
            helper.setBlock(pos, ModRegistry.RESONATOR.get());
        }
        helper.setBlock(CORE, ModRegistry.QUANTUM_CORE.get());
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void ritualCreatesNetworkFromPlainStack(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 20));
        if (!core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()))) {
            helper.fail("Ritual refused to start on a valid structure");
        }
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void ritualBurnsShardOnEmptyCircle(GameTestHelper helper) {
        buildCircle(helper);
        if (!core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()))) {
            helper.fail("Commitment is commitment: the shard goes in even over an empty circle");
        }
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void coreRefusesShardWithoutStructure(GameTestHelper helper) {
        helper.setBlock(CORE, ModRegistry.QUANTUM_CORE.get()); // no floor, no resonators
        ItemStack shard = new ItemStack(ModRegistry.QUANTUM_SHARD.get(), 3);
        if (core(helper).startRitual(shard)) {
            helper.fail("Ritual started without a structure");
        }
        if (shard.getCount() != 3) {
            helper.fail("Shard consumed by a machine that does not exist");
        }
        helper.succeed();
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void ritualExpandsNetworkWhenAllWindowsPresent(GameTestHelper helper) {
        buildCircle(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        ItemStack plain = new ItemStack(Items.BREAD, 12);
        int id = networks.createNetwork(plain);
        resonator(helper, 0).setItem(0, makeWindow(helper, id, 1, plain));
        resonator(helper, 1).setItem(0, makeWindow(helper, id, 2, plain));
        if (!core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()))) {
            helper.fail("Ritual refused to start");
        }
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void ritualFailsWhenAWindowIsMissing(GameTestHelper helper) {
        buildCircle(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        ItemStack plain = new ItemStack(Items.BREAD, 12);
        int id = networks.createNetwork(plain);
        ItemStack window1 = makeWindow(helper, id, 1, plain);
        ItemStack window2 = makeWindow(helper, id, 2, plain); // stays "elsewhere in the world"
        resonator(helper, 0).setItem(0, window1);
        core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void ritualFailsOnTwoPlainStacks(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 5));
        resonator(helper, 1).setItem(0, new ItemStack(Items.APPLE, 7));
        core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void ritualRefusesDamageableItems(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.IRON_PICKAXE));
        core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
    public void circleIsLockedDuringRitual(GameTestHelper helper) {
        buildCircle(helper);
        resonator(helper, 0).setItem(0, new ItemStack(Items.BREAD, 20));
        core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(10, () -> {
            if (!resonator(helper, 0).isLockedByRitual() || !resonator(helper, 3).isLockedByRitual()) {
                helper.fail("Circle must be locked while the ritual runs");
            }
        });
        helper.runAfterDelay(VERDICT_TICKS + QuantumCoreBlockEntity.SUCCESS_TICKS + 2, () -> {
            if (resonator(helper, 0).isLockedByRitual()) {
                helper.fail("Circle still locked after the ritual ended");
            }
            helper.succeed();
        });
    }

    /** A full network occupies all four resonators — no vacancy, the cap is visible. */
    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 150)
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
        core(helper).startRitual(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(VERDICT_TICKS, () -> {
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
}
