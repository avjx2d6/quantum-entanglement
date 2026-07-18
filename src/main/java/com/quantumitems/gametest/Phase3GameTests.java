package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Phase 3: absorption, craft-consumption guard, window death on the ground. */
@PrefixGameTestTemplate(false)
public class Phase3GameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(Phase3GameTests.class);
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

    /** Plain items flow into the pool; both windows see the new count. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void absorbFillsPool(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, 10);

        int absorbed = engine.absorb(network.windowA(), plain, Integer.MAX_VALUE);

        helper.assertTrue(absorbed == 10, "all 10 must be absorbed");
        helper.assertTrue(plain.isEmpty(), "plain stack must be consumed");
        helper.assertTrue(network.windowA().getCount() == 40, "window A must show 40");
        helper.assertTrue(network.windowB().getCount() == 40, "window B must show 40");
        helper.assertTrue(networks(helper).network(network.id()).pool == 40, "pool must be 40");
        helper.succeed();
    }

    /** Absorption is capped by the item's max stack size; the leftover stays plain. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void absorbRespectsCap(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 60);
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.BREAD, 10);

        int absorbed = engine.absorb(network.windowA(), plain, Integer.MAX_VALUE);

        helper.assertTrue(absorbed == 4, "only 4 fit under the cap of 64");
        helper.assertTrue(plain.getCount() == 6, "leftover must stay plain");
        helper.assertTrue(networks(helper).network(network.id()).pool == 64, "pool must be full");
        helper.succeed();
    }

    /** Different items or different components must not be absorbed. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void absorbRejectsMismatches(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        QuantumEngine engine = QuantumEngine.onServerThread();

        ItemStack wrongItem = new ItemStack(Items.COBBLESTONE, 10);
        ItemStack renamed = new ItemStack(Items.BREAD, 10);
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("special"));

        helper.assertTrue(engine.absorb(network.windowA(), wrongItem, 64) == 0, "wrong item must be rejected");
        helper.assertTrue(engine.absorb(network.windowA(), renamed, 64) == 0, "renamed item must be rejected");
        helper.assertTrue(networks(helper).network(network.id()).pool == 30, "pool untouched");
        helper.succeed();
    }

    /**
     * Crafting-consumption guard: with pool == 1 the ingredient pre-collapses
     * to plain, so the vanilla removeItem(slot, 1) consumes it honestly and
     * the other windows vanish — no phantom item.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void craftGuardPreventsLastItemDupe(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        QuantumEngine engine = QuantumEngine.onServerThread();
        SimpleContainer grid = new SimpleContainer(9);
        grid.setItem(4, network.windowA());

        // what ServerEvents.onItemCrafted does for each linked grid stack:
        engine.precollapseIfSingleton(grid.getItem(4));
        // vanilla ingredient consumption follows
        ItemStack consumed = grid.removeItem(4, 1);

        helper.assertTrue(!consumed.isEmpty(), "the ingredient must actually be consumed");
        helper.assertTrue(!consumed.has(ModRegistry.QUANTUM_LINK.get()), "consumed item must be plain");
        helper.assertTrue(network.windowB().isEmpty(), "other window must vanish");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network must be gone");
        helper.succeed();
    }

    /**
     * A destroyed window retires its member; the pool survives in the others.
     * Three members here on purpose: retiring down to ONE survivor now
     * collapses it to plain (lone-member anomaly, see AnomalyGameTests).
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void windowDeathRetiresMember(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 20);
        QuantumEngine engine = QuantumEngine.onServerThread();
        int memberC = networks(helper).addMember(network.id());
        ItemStack windowC = network.windowA().copy();
        windowC.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(network.id(), memberC));
        engine.adopt(windowC);

        engine.windowDestroyed(network.windowA());

        QuantumNetworks.Network entry = networks(helper).network(network.id());
        helper.assertTrue(entry != null, "network must survive");
        helper.assertTrue(entry.pool == 20, "pool untouched — a window burned, not the items");
        helper.assertTrue(!entry.aliveMembers.contains(1), "member #1 must retire");
        helper.assertTrue(entry.aliveMembers.contains(2), "member #2 must remain");
        helper.assertTrue(entry.aliveMembers.contains(memberC), "member #3 must remain");
        helper.succeed();
    }

    /** Destroying the LAST window kills the pool with it. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void lastWindowDeathKillsNetwork(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 20);
        QuantumEngine engine = QuantumEngine.onServerThread();

        engine.windowDestroyed(network.windowA());
        engine.windowDestroyed(network.windowB());

        helper.assertTrue(networks(helper).network(network.id()) == null,
                "network and pool must die with the last window");
        helper.succeed();
    }

    /** Pickup absorption respects vanilla slot order: an earlier plain stack with room wins. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void pickupPrefersEarlierPlainStack(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        QuantumEngine engine = QuantumEngine.onServerThread();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(Items.BREAD, 30)); // plain, plenty of room
        player.getInventory().setItem(5, network.windowA());

        ItemStack picked = new ItemStack(Items.BREAD, 5);
        helper.assertTrue(engine.absorbPickup(player.getInventory(), picked) == 0,
                "plain stack with room comes first — vanilla must handle the whole pickup");
        helper.assertTrue(picked.getCount() == 5, "picked stack untouched");

        player.getInventory().setItem(0, new ItemStack(Items.BREAD, 64)); // now full
        helper.assertTrue(engine.absorbPickup(player.getInventory(), picked) == 5,
                "with the plain stack full, the window absorbs everything");
        helper.assertTrue(networks(helper).network(network.id()).pool == 35, "pool must be 35");
        helper.succeed();
    }

    /** Absorption composes with vanilla: only the unmergeable remainder enters the pool. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void pickupAbsorbsOnlyUnmergeableRemainder(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        QuantumEngine engine = QuantumEngine.onServerThread();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(Items.BREAD, 60)); // room for 4
        player.getInventory().setItem(5, network.windowA());

        ItemStack picked = new ItemStack(Items.BREAD, 10);
        int absorbed = engine.absorbPickup(player.getInventory(), picked);

        helper.assertTrue(absorbed == 6, "vanilla merges 4, the window absorbs the remaining 6");
        helper.assertTrue(picked.getCount() == 4, "4 items stay for the vanilla pickup");
        helper.assertTrue(networks(helper).network(network.id()).pool == 36, "pool must be 36");
        helper.succeed();
    }

    /**
     * Rule 5: a creative packet replacing a window slot with something that no
     * longer carries the link retires that member — never a ghost network.
     * Siblings live on; and once retirement leaves a LONE survivor, it
     * collapses to plain right there (items conserved, no half-ghosts).
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void creativeReplaceRetiresMember(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        QuantumEngine engine = QuantumEngine.onServerThread();
        int memberC = networks(helper).addMember(network.id());
        ItemStack windowC = network.windowA().copy();
        windowC.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(network.id(), memberC));
        engine.adopt(windowC);

        // the client merged window #1 into a plain stack and uploaded plain 25
        engine.creativeSlotReplaced(network.windowA(), new ItemStack(Items.BREAD, 25));

        QuantumNetworks.Network entry = networks(helper).network(network.id());
        helper.assertTrue(entry != null, "network survives while members remain");
        helper.assertTrue(!entry.aliveMembers.contains(1), "replaced member must retire");
        helper.assertTrue(entry.aliveMembers.contains(2), "sibling member lives on");
        helper.assertTrue(entry.pool == 6, "pool untouched");
        helper.assertTrue(network.windowB().getCount() == 6, "sibling window keeps working");

        // destroying window #2 leaves windowC alone -> anomaly, network deleted
        engine.creativeSlotReplaced(network.windowB(), ItemStack.EMPTY);
        helper.assertTrue(networks(helper).network(network.id()) == null,
                "lone survivor -> network deleted, no ghost");
        helper.assertTrue(windowC.isEmpty(),
                "anomalous clone wipes with its network, got " + windowC);
        helper.succeed();
    }

    /** Creative slot packets are direct pool edits: count down = extraction, up = conjuring. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void creativeUpdateEditsPool(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 64);
        QuantumEngine engine = QuantumEngine.onServerThread();

        // the creative client split the window locally and uploaded count 25
        ItemStack incoming = network.windowA().copy();
        incoming.setCount(25); // transient copy: passes through untouched
        engine.creativeUpdate(incoming);

        helper.assertTrue(networks(helper).network(network.id()).pool == 25, "pool must follow the edit");
        helper.assertTrue(network.windowB().getCount() == 25, "other window must show 25");

        // consuming from the adopted instance keeps syncing
        incoming.shrink(5);
        helper.assertTrue(networks(helper).network(network.id()).pool == 20, "pool must be 20");
        helper.assertTrue(network.windowB().getCount() == 20, "other window must show 20");
        helper.succeed();
    }


    /** A stale copy burning must NOT retire the live member. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void staleCopyDeathIsIgnored(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 20);
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack staleCopy = network.windowA().copy();

        engine.windowDestroyed(staleCopy);

        QuantumNetworks.Network entry = networks(helper).network(network.id());
        helper.assertTrue(entry.aliveMembers.contains(1), "live member must survive a copy's death");
        helper.assertTrue(entry.pool == 20, "pool untouched");
        helper.succeed();
    }
}
