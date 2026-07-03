package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Hopper transfers with real ticking blocks — the NeoForge capability-hook paths. */
@PrefixGameTestTemplate(false)
public class HopperGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(HopperGameTests.class);
        }
    }

    private record TestNetwork(int id, ItemStack windowA, ItemStack windowB) {
    }

    private static TestNetwork makeNetwork(GameTestHelper helper, int count) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, count);
        int id = networks.createNetwork(plain);
        ItemStack windowA = plain.copy();
        windowA.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));
        ItemStack windowB = plain.copy();
        windowB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 2));
        engine.adopt(windowA);
        engine.adopt(windowB);
        return new TestNetwork(id, windowA, windowB);
    }

    private static QuantumNetworks networks(GameTestHelper helper) {
        return QuantumNetworks.get(helper.getLevel().getServer());
    }

    /** Hopper pulls plain items out of a window in the chest above it. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPullsPlainFromWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 20);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        chest.setItem(0, network.windowA());

        helper.runAfterDelay(40, () -> {
            HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
            int moved = 0;
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                ItemStack stack = hopper.getItem(slot);
                helper.assertTrue(!stack.has(ModRegistry.QUANTUM_LINK.get()), "hopper must receive plain items");
                moved += stack.getCount();
            }
            helper.assertTrue(moved > 0, "hopper must actually pull items");
            int pool = networks(helper).network(network.id()).pool;
            helper.assertTrue(pool == 20 - moved, "pool must shrink by exactly the moved amount");
            helper.assertTrue(network.windowB().getCount() == pool, "other window must track the pool");
            helper.succeed();
        });
    }

    /** Pulled plain items merge into an existing partial plain stack in the hopper. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPullMergesIntoPartialStack(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 20);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        chest.setItem(0, network.windowA());
        hopper.setItem(0, new ItemStack(Items.BREAD, 10));

        helper.runAfterDelay(40, () -> {
            helper.assertTrue(hopper.getItem(0).getCount() > 10,
                    "pulled items must merge into the partial stack, not spread out");
            for (int slot = 1; slot < hopper.getContainerSize(); slot++) {
                helper.assertTrue(hopper.getItem(slot).isEmpty(),
                        "no one-item stacks scattered across other slots");
            }
            helper.succeed();
        });
    }

    /** Pulling the last pooled item moves the window itself into the hopper. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPullsLastItemAsWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        chest.setItem(0, network.windowA());

        helper.runAfterDelay(40, () -> {
            HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
            boolean windowInHopper = false;
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                if (hopper.getItem(slot).has(ModRegistry.QUANTUM_LINK.get())) {
                    windowInHopper = true;
                }
            }
            helper.assertTrue(windowInHopper, "the window itself must move into the hopper");
            helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
            helper.assertTrue(networks(helper).network(network.id()) != null, "network must survive the move");
            helper.assertTrue(networks(helper).network(network.id()).pool == 1, "pool unchanged");
            helper.succeed();
        });
    }

    /** A hopper pushing a window into a FULL container must not leak the pool (the drain bug). */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPushIntoFullContainerLosesNothing(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.HOPPER); // faces down into the chest
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.STICK, 64)); // no room for bread
        }
        hopper.setItem(0, network.windowA());

        helper.runAfterDelay(60, () -> {
            helper.assertTrue(networks(helper).network(network.id()) != null, "network must survive");
            helper.assertTrue(networks(helper).network(network.id()).pool == 10,
                    "pool must not drain while the target is full");
            boolean windowStillThere = false;
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                if (hopper.getItem(slot).has(ModRegistry.QUANTUM_LINK.get())) {
                    windowStillThere = true;
                }
            }
            helper.assertTrue(windowStillThere, "the window must stay in the hopper");
            helper.succeed();
        });
    }

    /** A hopper pushes plain items out of a window into the chest below. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPushDeliversPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        hopper.setItem(0, network.windowA());

        helper.runAfterDelay(40, () -> {
            int delivered = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                helper.assertTrue(!stack.has(ModRegistry.QUANTUM_LINK.get()), "chest must receive plain items");
                delivered += stack.getCount();
            }
            helper.assertTrue(delivered > 0, "hopper must actually push items");
            int pool = networks(helper).network(network.id()).pool;
            helper.assertTrue(pool == 10 - delivered, "pool must shrink by exactly the delivered amount");
            helper.assertTrue(network.windowB().getCount() == pool, "other window must track the pool");
            helper.succeed();
        });
    }
}
