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
 * members). The doctrine's universal fallback applies — the lone window
 * collapses to plain (count = pool, network dissolves). If the survivor has
 * no live instance (asleep in a shulker or creative-deleted), the network
 * must NOT be deleted blindly — that would zero the sleeper on wake-up;
 * instead the collapse happens at first touch, in reconcile.
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
    public void retirementLeavingOneMemberCollapsesLiveSurvivor(GameTestHelper helper) {
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

        if (windowA.has(ModRegistry.QUANTUM_LINK.get())) {
            helper.fail("Lone survivor must collapse to plain, still linked: " + windowA);
        }
        if (windowA.getCount() != 10) {
            helper.fail("Collapse must conserve the pool, got " + windowA.getCount());
        }
        if (networks.network(id) != null) {
            helper.fail("Single-member network must dissolve");
        }
        helper.succeed();
    }

    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void sleepingSurvivorCollapsesAtFirstTouchNotBefore(GameTestHelper helper) {
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

        QuantumNetworks.Network network = networks.network(id);
        if (network == null) {
            helper.fail("Network with an untracked survivor must NOT be deleted "
                    + "(a sleeper would wipe to zero on wake-up — item loss)");
        }
        if (!network.aliveMembers.equals(java.util.Set.of(1))) {
            helper.fail("Expected lone member m1, got " + network.aliveMembers);
        }

        // The sleeper wakes: first touch reconciles — and collapses it to plain.
        engine.reconcile(sleeper);
        if (sleeper.has(ModRegistry.QUANTUM_LINK.get()) || sleeper.getCount() != 10) {
            helper.fail("Woken lone survivor must collapse to plain x10, got " + sleeper);
        }
        if (networks.network(id) != null) {
            helper.fail("Network must dissolve once the lone survivor collapses");
        }
        helper.succeed();
    }
}
