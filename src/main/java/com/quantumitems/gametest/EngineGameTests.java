package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Headless tests for the core sync engine: pool arithmetic, window moves,
 * dissolve, collapse and duplicate protection — all through the same vanilla
 * ItemStack calls that real gameplay uses.
 */
@PrefixGameTestTemplate(false)
public class EngineGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(EngineGameTests.class);
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

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void splitExtractsPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 64);
        ItemStack taken = network.windowA().split(10);

        helper.assertTrue(taken.getCount() == 10, "split portion must have 10 items");
        helper.assertTrue(!taken.has(ModRegistry.QUANTUM_LINK.get()), "split portion must be plain");
        helper.assertTrue(network.windowA().getCount() == 54, "window A must show pool 54");
        helper.assertTrue(network.windowB().getCount() == 54, "window B must show pool 54");
        helper.assertTrue(networks(helper).network(network.id()).pool == 54, "pool must be 54");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void wholeTakeMovesWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        ItemStack moved = network.windowA().split(30);

        helper.assertTrue(moved.has(ModRegistry.QUANTUM_LINK.get()), "whole take must keep the link");
        helper.assertTrue(moved.getCount() == 30, "moved window must show the pool");
        helper.assertTrue(network.windowA().isEmpty(), "old instance must be empty");
        helper.assertTrue(network.windowB().getCount() == 30, "other window untouched");
        helper.assertTrue(networks(helper).network(network.id()).pool == 30, "pool unchanged by a move");

        // the moved instance must now be the live window: consuming from it syncs
        moved.shrink(5);
        helper.assertTrue(network.windowB().getCount() == 25, "consumption from moved window must sync");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void consumptionSyncsAllWindows(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        network.windowA().shrink(1);

        helper.assertTrue(network.windowA().getCount() == 9, "window A must show 9");
        helper.assertTrue(network.windowB().getCount() == 9, "window B must show 9");
        helper.assertTrue(networks(helper).network(network.id()).pool == 9, "pool must be 9");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void consumingLastItemDissolves(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 3);
        network.windowA().shrink(3);

        helper.assertTrue(network.windowA().isEmpty(), "window A must be empty");
        helper.assertTrue(network.windowB().isEmpty(), "window B must vanish too");
        helper.assertTrue(!network.windowB().has(ModRegistry.QUANTUM_LINK.get()), "link must be stripped");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network must be gone");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void renameCollapsesNetwork(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        QuantumEngine engine = QuantumEngine.onServerThread();

        network.windowA().set(DataComponents.CUSTOM_NAME, Component.literal("renamed"));
        engine.reconcile(network.windowA());

        helper.assertTrue(!network.windowA().has(ModRegistry.QUANTUM_LINK.get()), "collapsed stack must be plain");
        helper.assertTrue(network.windowA().getCount() == 16, "collapsed stack must take the whole pool");
        helper.assertTrue(network.windowB().isEmpty(), "other window must vanish");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network must be gone");
        helper.succeed();
    }

    /**
     * Vanilla replaces slot instances with equal copies on packet round-trips
     * (creative slots, chunk reloads). The replacement must be adopted, never
     * punished — the pool keeps everything bounded anyway.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void replacedInstanceIsAdopted(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        QuantumEngine engine = QuantumEngine.onServerThread();

        // simulate ServerboundSetCreativeModeSlotPacket: a fresh equal instance
        // takes the place of the canonical one while the old one is still alive
        ItemStack replacement = network.windowA().copy();
        engine.reconcile(replacement);

        helper.assertTrue(!replacement.isEmpty(), "replacement must survive reconcile");
        helper.assertTrue(replacement.getCount() == 16, "replacement must show the pool");

        // and it is the live window now: consumption syncs the network
        replacement.shrink(1);
        helper.assertTrue(network.windowB().getCount() == 15, "other window must sync to 15");
        helper.assertTrue(networks(helper).network(network.id()).pool == 15, "pool must be 15");
        helper.succeed();
    }

    /** Extraction through several coexisting instances is bounded by the pool. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void coexistingInstancesArePoolBounded(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        ItemStack clone = network.windowA().copy(); // creative-style clone

        ItemStack fromClone = clone.split(6);       // adopts the clone
        ItemStack fromOriginal = network.windowA().split(6); // adopts the original back

        helper.assertTrue(fromClone.getCount() == 6 && !fromClone.has(ModRegistry.QUANTUM_LINK.get()),
                "first extraction gives 6 plain");
        helper.assertTrue(fromOriginal.getCount() == 6 && !fromOriginal.has(ModRegistry.QUANTUM_LINK.get()),
                "second extraction gives 6 plain");
        helper.assertTrue(networks(helper).network(network.id()).pool == 4,
                "pool must be 16 - 6 - 6 = 4: no duplication through clones");
        helper.succeed();
    }

    /** Within one scan pass the second sighting of a member is cleaned up. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void scanWipesSecondSighting(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack clone = network.windowA().copy();

        java.util.Set<Long> seen = new java.util.HashSet<>();
        engine.reconcileScan(network.windowA(), seen);
        QuantumEngine.Status second = engine.reconcileScan(clone, seen);

        helper.assertTrue(second == QuantumEngine.Status.DUPLICATE, "second sighting must be flagged");
        helper.assertTrue(clone.isEmpty(), "second sighting must be wiped");
        helper.assertTrue(network.windowA().getCount() == 16, "first sighting untouched");
        helper.assertTrue(networks(helper).network(network.id()).pool == 16, "pool untouched");
        helper.succeed();
    }

    /** Returning a window to the inventory (menu close, pickup fallback) moves it whole. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void placeItemBackKeepsWindowWhole(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        player.getInventory().placeItemBackInInventory(network.windowA());

        ItemStack inInventory = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (!candidate.isEmpty()) {
                inInventory = candidate;
                break;
            }
        }
        helper.assertTrue(inInventory.has(ModRegistry.QUANTUM_LINK.get()), "window must land with its link");
        helper.assertTrue(inInventory.getCount() == 16, "window must show the pool");
        helper.assertTrue(networks(helper).network(network.id()).pool == 16, "pool untouched by the move");

        inInventory.shrink(2);
        helper.assertTrue(network.windowB().getCount() == 14, "landed window must be live: sync to 14");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void deadNetworkStackIsWiped(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 8);
        ItemStack strayWindow = network.windowA().copy();

        // dissolve the network by eating everything through window B
        network.windowB().shrink(8);
        helper.assertTrue(networks(helper).network(network.id()) == null, "network must be gone");

        // a stack from an unloaded chunk shows up later: touch wipes it
        strayWindow.split(1);
        helper.assertTrue(strayWindow.isEmpty(), "stack of a dead network must be wiped on touch");
        helper.succeed();
    }
}
