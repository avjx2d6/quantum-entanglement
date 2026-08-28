package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The anvil, which is the one vanilla menu that DESTROYS an input rather than
 * shrinking it: {@code AnvilMenu.onTake} calls {@code inputSlots.setItem(0,
 * EMPTY)} outright, and empties slot 1 the same way whenever the repair cost
 * meets or exceeds what is in it. Neither of those goes through setCount, so
 * neither reaches the hooks every other path in the mod relies on.
 *
 * <p>Every test here asserts the same thing in a different arrangement:
 * COUNT ITEMS BEFORE, COUNT ITEMS AFTER, they match. What the anvil is allowed
 * to do to a network is a design question; making items cease to exist is not.
 */
@PrefixGameTestTemplate(false)
public class AnvilGameTests {

    @EventBusSubscriber(modid = com.quantumitems.QuantumItemsMod.MOD_ID)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(AnvilGameTests.class);
        }
    }

    private record TestNetwork(int id, ItemStack windowA, ItemStack windowB) {
    }

    private static TestNetwork makeNetwork(GameTestHelper helper, Item item, int count) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(item, count);
        int id = networks.createNetwork(plain);
        ItemStack windowA = plain.copy();
        windowA.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));
        ItemStack windowB = plain.copy();
        windowB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 2));
        engine.adopt(windowA);
        engine.adopt(windowB);
        return new TestNetwork(id, windowA, windowB);
    }

    /** Pool of a network that may already have been dissolved; 0 once it is gone. */
    private static int pool(GameTestHelper helper, int id) {
        QuantumNetworks.Network network = QuantumNetworks.get(helper.getLevel().getServer()).network(id);
        return network == null ? 0 : network.pool;
    }

    /** An anvil menu looking at a real anvil, with a player rich enough to use it. */
    private static AnvilMenu openAnvil(GameTestHelper helper, Player player) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.ANVIL);
        player.experienceLevel = 60;
        return new AnvilMenu(1, player.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(pos)));
    }

    /**
     * Everything of the mod's that is loose in the world after an anvil
     * operation: the pool, plus whatever plain copies of the item are lying in
     * the menu, the cursor and the player.
     */
    private static int itemsInPlay(GameTestHelper helper, AnvilMenu menu, Player player, int id, Item item) {
        int total = pool(helper, id);
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (stack.is(item) && !stack.has(ModRegistry.QUANTUM_LINK.get())) {
                total += stack.getCount();
            }
        }
        ItemStack carried = menu.getCarried();
        if (carried.is(item) && !carried.has(ModRegistry.QUANTUM_LINK.get())) {
            total += carried.getCount();
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item) && !stack.has(ModRegistry.QUANTUM_LINK.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Renaming a window. The rename diverges the stack's components from the
     * network's snapshot, which is a collapse — the pool is supposed to come
     * out as plain items in the renamed stack. What must not happen is the
     * forty loaves going nowhere.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void renamingAWindowKeepsTheItems(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        TestNetwork network = makeNetwork(helper, Items.BREAD, 40);
        AnvilMenu menu = openAnvil(helper, player);

        menu.slots.get(0).set(network.windowA());
        menu.setItemName("renamed");
        menu.slotsChanged(menu.slots.get(0).container);

        ItemStack result = menu.slots.get(2).getItem();
        helper.assertTrue(!result.isEmpty(), "the anvil must offer a renamed result to take");
        menu.clicked(2, 0, ClickType.PICKUP, player);

        int after = itemsInPlay(helper, menu, player, network.id(), Items.BREAD);
        helper.assertTrue(after == 40,
                "40 bread went into the anvil, " + after + " came out");
        helper.succeed();
    }

    /**
     * A window used as the repair MATERIAL, partially consumed. The anvil
     * shrinks slot 1 by the repair cost, which should come off the pool and be
     * the only thing that changes.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void repairingWithAWindowSpendsOnlyTheCost(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        TestNetwork network = makeNetwork(helper, Items.IRON_INGOT, 40);
        AnvilMenu menu = openAnvil(helper, player);

        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(pickaxe.getMaxDamage() - 1);
        menu.slots.get(0).set(pickaxe);
        menu.slots.get(1).set(network.windowA());
        menu.slotsChanged(menu.slots.get(0).container);

        ItemStack result = menu.slots.get(2).getItem();
        helper.assertTrue(!result.isEmpty(), "a damaged pickaxe plus ingots must offer a repair");
        // How many ingots vanilla has decided to spend. Asserting a number of
        // our own here would only be testing our arithmetic against Mojang's;
        // what matters is that the pool moves by this and by nothing else.
        int cost = menu.repairItemCountCost;
        helper.assertTrue(cost > 0, "a repair must cost at least one ingot");
        menu.clicked(2, 0, ClickType.PICKUP, player);

        int after = itemsInPlay(helper, menu, player, network.id(), Items.IRON_INGOT);
        helper.assertTrue(after == 40 - cost,
                "the repair cost " + cost + " ingots, but " + (40 - after) + " left the pool");
        helper.assertTrue(network.windowB().getCount() == 40 - cost,
                "the sibling window must show the debited pool");
        helper.succeed();
    }

    /**
     * The same, with a pool small enough that the anvil takes the EMPTY branch
     * — {@code inputSlots.setItem(1, ItemStack.EMPTY)} — and destroys the whole
     * window outright rather than shrinking it.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void repairingWithTheLastOfAWindowLosesNothing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        TestNetwork network = makeNetwork(helper, Items.IRON_INGOT, 1);
        AnvilMenu menu = openAnvil(helper, player);

        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(pickaxe.getMaxDamage() - 1);
        menu.slots.get(0).set(pickaxe);
        menu.slots.get(1).set(network.windowA());
        menu.slotsChanged(menu.slots.get(0).container);

        helper.assertTrue(!menu.slots.get(2).getItem().isEmpty(), "one ingot must still repair");
        menu.clicked(2, 0, ClickType.PICKUP, player);

        // One ingot was legitimately spent on the repair, so the pool is now
        // empty and the network should be gone rather than left holding a
        // phantom count with no window anywhere.
        int after = itemsInPlay(helper, menu, player, network.id(), Items.IRON_INGOT);
        helper.assertTrue(after == 0,
                "one ingot repaired the pickaxe, but " + after + " ingots are still claimed somewhere");
        helper.assertTrue(!network.windowB().has(ModRegistry.QUANTUM_LINK.get())
                        || network.windowB().isEmpty(),
                "the sibling window must not still advertise items that were spent");
        helper.succeed();
    }
}
