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

    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void duplicateInstanceIsWiped(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        ItemStack dupe = network.windowA().copy();

        // count writes on copies pass through (transient copies are legitimate);
        // materialization touchpoints like split are where duplicates die
        ItemStack taken = dupe.split(1);

        helper.assertTrue(taken.isEmpty(), "split of a duplicate must yield nothing");
        helper.assertTrue(dupe.isEmpty(), "duplicate must be wiped");
        helper.assertTrue(!dupe.has(ModRegistry.QUANTUM_LINK.get()), "duplicate must lose the link");
        helper.assertTrue(network.windowA().getCount() == 16, "canonical window untouched");
        helper.assertTrue(networks(helper).network(network.id()).pool == 16, "pool untouched");
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
