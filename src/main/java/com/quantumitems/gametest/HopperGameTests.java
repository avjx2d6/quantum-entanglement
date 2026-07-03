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
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
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

    /**
     * A hopper pulling a single-item pool takes it as PLAIN, not a stray
     * window — automation normalises the last item and the network ends.
     * (Players holding a single linked item go through the menu path, which
     * keeps the link; this is the transport path.)
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPullsSingletonAsPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        chest.setItem(0, network.windowA());

        helper.runAfterDelay(40, () -> {
            HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
            int plain = 0;
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                ItemStack stack = hopper.getItem(slot);
                helper.assertTrue(!stack.has(ModRegistry.QUANTUM_LINK.get()), "no stray window in the hopper");
                plain += stack.getCount();
            }
            helper.assertTrue(plain == 1, "the single item arrives as one plain item");
            helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network ends with its last item");
            helper.succeed();
        });
    }

    /**
     * A FULL hopper pointed at a chest holding a single-item window must not
     * collapse it — the network (and its siblings) survive until a real
     * transfer happens, never from mere proximity.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void fullHopperDoesNotCollapseSingleton(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        chest.setItem(0, network.windowA());
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            hopper.setItem(slot, new ItemStack(Items.STICK, 64)); // no room for bread
        }

        helper.runAfterDelay(40, () -> {
            helper.assertTrue(networks(helper).network(network.id()) != null, "network must survive a full hopper");
            helper.assertTrue(networks(helper).network(network.id()).pool == 1, "pool unchanged");
            helper.assertTrue(chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get()), "the window stays a window");
            helper.assertTrue(network.windowB().getCount() == 1, "the sibling window is untouched");
            helper.succeed();
        });
    }

    /**
     * Draining a whole multi-item pool through a hopper must yield exactly that
     * many plain items merged together — no stray window and, crucially, no
     * dupe when the pool hits its last item while plain has already piled up.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperDrainsWholePoolToPlainNoDupe(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 3);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        chest.setItem(0, network.windowA());

        helper.runAfterDelay(80, () -> {
            HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
            int plain = 0;
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                ItemStack stack = hopper.getItem(slot);
                helper.assertTrue(!stack.has(ModRegistry.QUANTUM_LINK.get()),
                        "no linked stack may linger — the last item drains as plain");
                plain += stack.getCount();
            }
            helper.assertTrue(chest.getItem(0).isEmpty(), "source must be empty");
            helper.assertTrue(plain == 3, "exactly the 3 pooled items come out as plain, no dupe");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network ends with the pool");
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

    /**
     * A hopper feeding plain items into a container that holds a window merges
     * them into the pool — pushing plain "into the link" works through plain
     * vanilla insertion because the stacking hook makes them combine.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPushesPlainIntoWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.HOPPER);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        chest.setItem(0, network.windowA());
        hopper.setItem(0, new ItemStack(Items.BREAD, 5));

        helper.runAfterDelay(60, () -> {
            int pool = networks(helper).network(network.id()).pool;
            helper.assertTrue(pool == 15, "the 5 plain items must merge into the pool");
            helper.assertTrue(chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get()), "the window stays a window");
            helper.assertTrue(chest.getItem(0).getCount() == 15, "the window shows the grown pool");
            helper.assertTrue(network.windowB().getCount() == 15, "other window tracks the pool");
            helper.assertTrue(hopper.getItem(0).isEmpty(), "the hopper emptied its plain into the pool");
            helper.succeed();
        });
    }

    /**
     * A hopper feeding plain items into a window sitting in a FURNACE input
     * slot must merge them into the pool — sided containers (furnaces, most
     * modded machines via SidedInvWrapper) must honour the link just like a
     * plain chest does.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void hopperPushesPlainIntoWindowInFurnace(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.HOPPER); // above, faces down into the input slot
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        furnace.setItem(0, network.windowA()); // the window occupies the smelt input
        hopper.setItem(0, new ItemStack(Items.BREAD, 5));

        helper.runAfterDelay(60, () -> {
            int pool = networks(helper).network(network.id()).pool;
            helper.assertTrue(pool == 15, "the 5 plain items must merge into the pool");
            helper.assertTrue(furnace.getItem(0).has(ModRegistry.QUANTUM_LINK.get()), "the window stays a window");
            helper.assertTrue(furnace.getItem(0).getCount() == 15, "the window shows the grown pool");
            helper.assertTrue(network.windowB().getCount() == 15, "other window tracks the pool");
            helper.assertTrue(hopper.getItem(0).isEmpty(), "the hopper emptied its plain into the pool");
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
