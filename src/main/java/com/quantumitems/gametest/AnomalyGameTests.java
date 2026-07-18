package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A single-member network is an anomaly: creation always yields two windows
 * and survival has no member-retirement path (only creative edits retire
 * members). The author's ruling: a member that vanished anomalously either
 * died with the items or left the network taking them along — either way
 * the remaining clone must not keep serving the pool, so the network is
 * DELETED and the survivor wipes to zero (live one immediately, a sleeper
 * on wake-up via the ordinary dead-network reconciliation).
 */
@PrefixGameTestTemplate(false)
public class AnomalyGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(AnomalyGameTests.class);
        }
    }

    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void retirementLeavingOneMemberDeletesNetworkAndSurvivor(GameTestHelper helper) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, 10);
        int id = networks.createNetwork(plain);
        ItemStack windowA = plain.copy();
        windowA.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));
        ItemStack windowB = plain.copy();
        windowB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 2));
        engine.adopt(windowA);
        engine.adopt(windowB);

        engine.windowDestroyed(windowB); // creative-style retirement of m2

        if (networks.network(id) != null) {
            helper.fail("Single-member network must be deleted outright");
        }
        if (!windowA.isEmpty()) {
            helper.fail("Lone survivor must wipe with its network, got " + windowA);
        }
        helper.succeed();
    }

    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void sleepingSurvivorWipesOnWakeUp(GameTestHelper helper) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, 10);
        int id = networks.createNetwork(plain);
        // m1's window exists only as untracked NBT (a sleeper) — never adopted.
        ItemStack sleeper = plain.copy();
        sleeper.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));
        ItemStack windowB = plain.copy();
        windowB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 2));
        engine.adopt(windowB);

        engine.windowDestroyed(windowB);

        if (networks.network(id) != null) {
            helper.fail("Anomalous network must be deleted even with a sleeping survivor");
        }
        // The sleeper wakes: dead networkId -> ordinary reconciliation wipes it.
        engine.reconcile(sleeper);
        if (!sleeper.isEmpty()) {
            helper.fail("Woken anomalous clone must wipe to zero, got " + sleeper);
        }
        helper.succeed();
    }

    /** Saves predating the rule get cleaned at boot; reconcile is the safety net. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void preexistingLoneNetworkDiesOnFirstTouch(GameTestHelper helper) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, 10);
        int id = networks.createNetwork(plain);
        QuantumNetworks.Network network = networks.network(id);
        network.aliveMembers.remove(Integer.valueOf(2)); // simulate an old-save anomaly
        ItemStack lone = plain.copy();
        lone.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));

        engine.reconcile(lone);

        if (networks.network(id) != null) {
            helper.fail("Lone-member network must die at first touch");
        }
        if (!lone.isEmpty()) {
            helper.fail("Anomalous clone must wipe, got " + lone);
        }
        helper.succeed();
    }
}
