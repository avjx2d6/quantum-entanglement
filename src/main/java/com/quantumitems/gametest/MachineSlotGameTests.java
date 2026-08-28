package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
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
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The rule that keeps third-party autocrafting from duplicating: at most one
 * window of a network inside machine inventories at a time.
 *
 * <p>Driven against plain {@link ItemStackHandler}s rather than any particular
 * mod's machine, which is the point — that class is what Create, AE2, RS and
 * most others build their inventories on, so a rule enforced here holds for
 * machines this mod has never heard of. It is also the only way to test this at
 * all without taking on a dependency the size of Create.
 *
 * <p>What it does NOT prove is that a given mod routes its hand-placement
 * through {@code insertItem}. Most do; none of them promised to.
 */
@PrefixGameTestTemplate(false)
public class MachineSlotGameTests {

    @EventBusSubscriber(modid = com.quantumitems.QuantumItemsMod.MOD_ID)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(MachineSlotGameTests.class);
        }
    }

    private static ItemStack[] makeWindows(GameTestHelper helper, int pool, int windows) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.OAK_PLANKS, pool);
        int id = networks.createNetwork(plain);
        ItemStack[] out = new ItemStack[windows];
        for (int i = 0; i < windows; i++) {
            ItemStack window = plain.copy();
            window.set(ModRegistry.QUANTUM_LINK.get(),
                    new QuantumLinkData(id, i == 0 ? 1 : networks.addMember(id)));
            engine.adopt(window);
            out[i] = window;
        }
        return out;
    }

    /** One window in one machine: the depot, the basin, the single crafter. Must work. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void oneWindowMayLiveInAMachine(GameTestHelper helper) {
        ItemStack[] windows = makeWindows(helper, 8, 2);
        ItemStackHandler depot = new ItemStackHandler(1);

        ItemStack leftover = depot.insertItem(0, windows[0], false);
        helper.assertTrue(leftover.isEmpty(), "a machine must accept the first window whole");
        helper.assertTrue(depot.getStackInSlot(0).has(ModRegistry.QUANTUM_LINK.get()),
                "and it must still be a window in there, not plain items");
        helper.succeed();
    }

    /** The second window of the same network is refused — this is the doubled grid. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void secondWindowOfANetworkIsRefused(GameTestHelper helper) {
        ItemStack[] windows = makeWindows(helper, 1, 3);
        ItemStackHandler crafterA = new ItemStackHandler(1);
        ItemStackHandler crafterB = new ItemStackHandler(1);
        ItemStackHandler crafterC = new ItemStackHandler(1);

        helper.assertTrue(crafterA.insertItem(0, windows[0], false).isEmpty(),
                "the first crafter takes its window");
        ItemStack second = crafterB.insertItem(0, windows[1], false);
        ItemStack third = crafterC.insertItem(0, windows[2], false);

        helper.assertTrue(!second.isEmpty() && crafterB.getStackInSlot(0).isEmpty(),
                "a second window of the same network must be refused, not stored");
        helper.assertTrue(!third.isEmpty() && crafterC.getStackInSlot(0).isEmpty(),
                "and so must a third");
        helper.assertTrue(second.has(ModRegistry.QUANTUM_LINK.get()),
                "the refused window comes back untouched — refusing must not destroy it");
        helper.succeed();
    }

    /** Simulation has to answer the same as the real thing, or handlers loop. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void simulationAgreesWithTheRealInsert(GameTestHelper helper) {
        ItemStack[] windows = makeWindows(helper, 8, 2);
        ItemStackHandler first = new ItemStackHandler(1);
        ItemStackHandler second = new ItemStackHandler(1);
        first.insertItem(0, windows[0], false);

        helper.assertTrue(!second.insertItem(0, windows[1], true).isEmpty(),
                "a simulated insert of the second window must also come back refused");
        helper.succeed();
    }

    /** Different networks have separate pools and nothing to double-count. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void differentNetworksMayShareMachines(GameTestHelper helper) {
        ItemStack[] first = makeWindows(helper, 8, 1);
        ItemStack[] second = makeWindows(helper, 8, 1);
        ItemStackHandler crafterA = new ItemStackHandler(1);
        ItemStackHandler crafterB = new ItemStackHandler(1);

        helper.assertTrue(crafterA.insertItem(0, first[0], false).isEmpty(), "first network in");
        helper.assertTrue(crafterB.insertItem(0, second[0], false).isEmpty(),
                "a window of a DIFFERENT network is not the doubled case and must be accepted");
        helper.succeed();
    }

    /**
     * The claim has to lapse, or one window in a machine would lock its network
     * out of every machine forever — including after the machine gives it back.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void theClaimLapsesWhenTheMachineIsEmptied(GameTestHelper helper) {
        ItemStack[] windows = makeWindows(helper, 8, 2);
        ItemStackHandler machine = new ItemStackHandler(1);
        ItemStackHandler other = new ItemStackHandler(1);
        machine.insertItem(0, windows[0], false);

        // Extraction always yields plain and ends the network's stay here.
        machine.extractItem(0, 64, false);
        machine.setStackInSlot(0, ItemStack.EMPTY);

        helper.assertTrue(other.insertItem(0, windows[1], false).isEmpty(),
                "with the machine emptied, the network may occupy one again");
        helper.succeed();
    }
}
