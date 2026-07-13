package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * The capability extraction paths modded transports use (Create chutes,
 * funnels, pipes, machine inventories). Rule 2 generalized: extraction only
 * ever yields PLAIN — partial takes debit the pool, a whole take is a full
 * extraction (network ends honestly), simulations get plain probes, and the
 * canonical window instance is never replaced by raw copies.
 */
@PrefixGameTestTemplate(false)
public class CapabilityGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(CapabilityGameTests.class);
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

    /** InvWrapper simulate must probe with PLAIN — a linked probe leaks into mod logic. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void invWrapperSimulateProbesPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        InvWrapper wrapper = new InvWrapper(chest);

        ItemStack probe = wrapper.extractItem(0, 1, true);

        helper.assertTrue(!probe.has(ModRegistry.QUANTUM_LINK.get()), "simulate must return a plain probe");
        helper.assertTrue(probe.getCount() == 1, "probe sized to the request");
        helper.assertTrue(networks(helper).network(network.id()).pool == 16, "pool untouched by simulate");
        helper.assertTrue(chest.getItem(0).getCount() == 16, "window untouched by simulate");
        helper.succeed();
    }

    /** InvWrapper full extraction yields PLAIN and ends the network — never the window. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void invWrapperFullExtractYieldsPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        InvWrapper wrapper = new InvWrapper(chest);

        ItemStack taken = wrapper.extractItem(0, 64, false);

        helper.assertTrue(!taken.has(ModRegistry.QUANTUM_LINK.get()),
                "a whole take is a full extraction: plain out, never the window");
        helper.assertTrue(taken.getCount() == 16, "all 16 pooled items come out, conserved");
        helper.assertTrue(chest.getItem(0).isEmpty(), "slot emptied");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network ends honestly");
        helper.assertTrue(network.windowB().isEmpty(), "sibling wiped");
        helper.succeed();
    }

    /** ItemStackHandler partial extraction: plain out, pool debited, the CANONICAL instance stays. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void handlerPartialExtractKeepsCanonicalWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, network.windowA());

        ItemStack taken = handler.extractItem(0, 4, false);

        helper.assertTrue(!taken.has(ModRegistry.QUANTUM_LINK.get()), "extracted items must be plain");
        helper.assertTrue(taken.getCount() == 4, "4 items out");
        ItemStack inSlot = handler.getStackInSlot(0);
        helper.assertTrue(inSlot == network.windowA(), "the canonical instance must stay in the slot");
        helper.assertTrue(inSlot.getCount() == 12, "window shows pool 12");
        helper.assertTrue(networks(helper).network(network.id()).pool == 12, "pool must be 12");
        helper.assertTrue(network.windowB().getCount() == 12, "sibling tracks the pool");

        // a follow-up write to the slot stack must still reach the pool (no orphaning)
        inSlot.shrink(2);
        helper.assertTrue(networks(helper).network(network.id()).pool == 10, "still wired to the pool");
        helper.succeed();
    }

    /** ItemStackHandler whole extraction yields PLAIN and ends the network. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void handlerFullExtractYieldsPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, network.windowA());

        ItemStack taken = handler.extractItem(0, 64, false);

        helper.assertTrue(!taken.has(ModRegistry.QUANTUM_LINK.get()), "plain out, never the window");
        helper.assertTrue(taken.getCount() == 16, "all 16 out, conserved");
        helper.assertTrue(handler.getStackInSlot(0).isEmpty(), "slot emptied");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network ends honestly");
        helper.succeed();
    }

    /** ItemStackHandler simulate: plain probes both for partial and whole requests. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void handlerSimulateProbesPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, network.windowA());

        ItemStack partial = handler.extractItem(0, 4, true);
        ItemStack whole = handler.extractItem(0, 64, true);

        helper.assertTrue(!partial.has(ModRegistry.QUANTUM_LINK.get()) && partial.getCount() == 4,
                "partial simulate: plain probe of 4");
        helper.assertTrue(!whole.has(ModRegistry.QUANTUM_LINK.get()) && whole.getCount() == 16,
                "whole simulate: plain probe of 16");
        helper.assertTrue(networks(helper).network(network.id()).pool == 16, "pool untouched");
        helper.assertTrue(handler.getStackInSlot(0) == network.windowA()
                && handler.getStackInSlot(0).getCount() == 16, "window untouched");
        helper.succeed();
    }

    /** ItemStackHandler insert of plain into a window slot absorbs into the pool. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void handlerInsertPlainAbsorbsIntoWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, network.windowA());

        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.BREAD, 5), false);

        helper.assertTrue(remainder.isEmpty(), "all 5 must be accepted");
        helper.assertTrue(handler.getStackInSlot(0).has(ModRegistry.QUANTUM_LINK.get()), "window stays a window");
        helper.assertTrue(networks(helper).network(network.id()).pool == 21, "pool absorbs to 21");
        helper.assertTrue(network.windowB().getCount() == 21, "sibling tracks the pool");
        helper.succeed();
    }
}
