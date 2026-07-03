package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side reproductions of real GUI interactions: cursor clicks through
 * an actual ChestMenu and hotbar drops through Inventory.removeFromSelected —
 * the exact paths a survival player exercises.
 */
@PrefixGameTestTemplate(false)
public class MenuGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(MenuGameTests.class);
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

    /** Left-click pickup of the whole window into the cursor — must just work. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void cursorPicksUpWholeWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 40);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.PICKUP, player);

        ItemStack carried = menu.getCarried();
        helper.assertTrue(carried.has(ModRegistry.QUANTUM_LINK.get()), "cursor must hold the window");
        helper.assertTrue(carried.getCount() == 40, "cursor must hold the whole pool");
        helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
        helper.assertTrue(networks(helper).network(network.id()).pool == 40, "pool unchanged");

        // the carried instance is the live window now: place it into another slot
        menu.clicked(5, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "cursor must be empty after placing");
        ItemStack placed = chest.getItem(5);
        helper.assertTrue(placed.has(ModRegistry.QUANTUM_LINK.get()), "placed stack must keep the link");

        // and consuming from it must sync the other window
        placed.shrink(4);
        helper.assertTrue(network.windowB().getCount() == 36, "other window must show 36");
        helper.succeed();
    }

    /** Right-click splits half into the cursor — the half must be plain, pool halved. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void rightClickHalfGivesPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 40);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 1, ClickType.PICKUP, player);

        ItemStack carried = menu.getCarried();
        helper.assertTrue(!carried.has(ModRegistry.QUANTUM_LINK.get()), "split half must be plain");
        helper.assertTrue(carried.getCount() == 20, "cursor must hold 20 plain items");
        helper.assertTrue(chest.getItem(0).getCount() == 20, "window must show pool 20");
        helper.assertTrue(network.windowB().getCount() == 20, "other window must show pool 20");
        helper.assertTrue(networks(helper).network(network.id()).pool == 20, "pool must be 20");
        helper.succeed();
    }

    /** Q on the hotbar: one plain item drops, pool decrements, windows sync. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void qDropExtractsOnePlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 40);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, network.windowA());
        player.getInventory().selected = 0;

        ItemStack dropped = player.getInventory().removeFromSelected(false);

        helper.assertTrue(!dropped.isEmpty(), "one item must actually come out");
        helper.assertTrue(dropped.getCount() == 1, "exactly one item drops");
        helper.assertTrue(!dropped.has(ModRegistry.QUANTUM_LINK.get()), "dropped item must be plain");
        helper.assertTrue(network.windowA().getCount() == 39, "hand window must show 39");
        helper.assertTrue(network.windowB().getCount() == 39, "other window must show 39");

        // drop again — still one plain item, no full-stack dupe
        ItemStack droppedAgain = player.getInventory().removeFromSelected(false);
        helper.assertTrue(droppedAgain.getCount() == 1, "second drop is one item too");
        helper.assertTrue(!droppedAgain.has(ModRegistry.QUANTUM_LINK.get()), "second drop must be plain");
        helper.assertTrue(networks(helper).network(network.id()).pool == 38, "pool must be 38");
        helper.succeed();
    }

    /** Ctrl+Q: the whole window flies out with the link intact, pool untouched. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void ctrlQDropMovesWholeWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 25);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, network.windowA());
        player.getInventory().selected = 0;

        ItemStack dropped = player.getInventory().removeFromSelected(true);

        helper.assertTrue(dropped.has(ModRegistry.QUANTUM_LINK.get()), "whole drop keeps the link");
        helper.assertTrue(dropped.getCount() == 25, "whole pool flies out");
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "hand slot must be empty");
        helper.assertTrue(networks(helper).network(network.id()).pool == 25, "pool unchanged by the move");

        // the dropped instance is the live window: consuming from it syncs
        dropped.shrink(5);
        helper.assertTrue(network.windowB().getCount() == 20, "other window must show 20");
        helper.succeed();
    }

    /**
     * Vanilla constantly sizes transient copies of stacks (copyWithCount,
     * simulated extractions). Those must pass through untouched — regression
     * test for the wiped-simulation bug that froze slots.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void transientCopyIsLeftAlone(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 40);

        ItemStack simulated = network.windowA().copy();
        simulated.setCount(1); // ItemHandlerHelper.copyStackWithSize pattern

        helper.assertTrue(simulated.getCount() == 1, "simulated copy must get its count");
        helper.assertTrue(!simulated.isEmpty(), "simulated copy must not be wiped");
        helper.assertTrue(network.windowA().getCount() == 40, "canonical window untouched");
        helper.assertTrue(networks(helper).network(network.id()).pool == 40, "pool untouched");
        helper.succeed();
    }

    /**
     * Carrying a window and left-clicking a matching plain stack absorbs that
     * stack into the pool — the window is a sink in either direction, and the
     * network is never destroyed by combining like-items (regression for the
     * bug where clicking a window onto plain bread dissolved the network).
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowLeftClickAbsorbsPlainSlot(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 20));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(chest.getItem(0).isEmpty(), "the whole plain slot must be absorbed");
        helper.assertTrue(menu.getCarried().has(ModRegistry.QUANTUM_LINK.get()), "cursor keeps the window");
        helper.assertTrue(menu.getCarried().getCount() == 30, "window must show pool 30");
        helper.assertTrue(network.windowB().getCount() == 30, "other window must show pool 30");
        helper.assertTrue(networks(helper).network(network.id()).pool == 30, "pool must be 30");
        helper.succeed();
    }

    /**
     * Absorption tops the pool up to the max stack size and leaves the rest of
     * the plain stack behind — the network survives even when the slot holds
     * more than the pool can take.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowAbsorbCapsAtMaxStack(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 60);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 40)); // only 4 fit into the pool
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(chest.getItem(0).getCount() == 36, "slot keeps the overflow the pool can't hold");
        helper.assertTrue(menu.getCarried().getCount() == 64, "window must fill to its cap");
        helper.assertTrue(networks(helper).network(network.id()) != null, "network must survive");
        helper.assertTrue(networks(helper).network(network.id()).pool == 64, "pool must be 64");
        helper.succeed();
    }

    /** Clicking a plain stack onto a window absorbs it into the pool (event path). */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void clickAbsorbsPlainIntoWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(new ItemStack(Items.BREAD, 10));

        menu.clicked(0, 0, ClickType.PICKUP, player); // left click plain onto window

        helper.assertTrue(menu.getCarried().isEmpty(), "carried plain must be fully absorbed");
        helper.assertTrue(chest.getItem(0).getCount() == 40, "window must show 40");
        helper.assertTrue(network.windowB().getCount() == 40, "other window must show 40");
        helper.assertTrue(networks(helper).network(network.id()).pool == 40, "pool must be 40");
        helper.succeed();
    }

    /** Right click absorbs exactly one item. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void rightClickAbsorbsOne(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(new ItemStack(Items.BREAD, 10));

        menu.clicked(0, 1, ClickType.PICKUP, player); // right click plain onto window

        helper.assertTrue(menu.getCarried().getCount() == 9, "carried must lose exactly one");
        helper.assertTrue(networks(helper).network(network.id()).pool == 31, "pool must be 31");
        helper.succeed();
    }

    /**
     * Right-clicking a carried window onto an EMPTY slot extracts one plain
     * item through the vanilla split path — the way a player spends the pool.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowRightClickOnEmptyExtractsOne(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 1, ClickType.PICKUP, player); // right click empty slot: vanilla split(1)

        helper.assertTrue(chest.getItem(0).getCount() == 1, "one plain item must be placed");
        helper.assertTrue(!chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get()), "placed item must be plain");
        helper.assertTrue(menu.getCarried().has(ModRegistry.QUANTUM_LINK.get()), "cursor keeps the window");
        helper.assertTrue(menu.getCarried().getCount() == 29, "window must show pool 29");
        helper.assertTrue(networks(helper).network(network.id()).pool == 29, "pool must be 29");
        helper.succeed();
    }

    /**
     * Right-clicking a carried window onto a matching PLAIN stack absorbs one
     * item into the pool — the window is a sink in either direction, so the
     * plain flows into the network rather than the window being deposited out.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowRightClickOnPlainAbsorbsOne(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 1, ClickType.PICKUP, player); // right click window onto plain

        helper.assertTrue(chest.getItem(0).getCount() == 9, "slot must lose exactly one");
        helper.assertTrue(menu.getCarried().getCount() == 31, "window must show pool 31");
        helper.assertTrue(networks(helper).network(network.id()).pool == 31, "pool must be 31");
        helper.succeed();
    }

    /** Shift-click through a real menu: the window travels whole, link intact. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void shiftClickMovesWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 40);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);

        helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
        ItemStack inInventory = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (!candidate.isEmpty()) {
                inInventory = candidate;
                break;
            }
        }
        helper.assertTrue(inInventory.has(ModRegistry.QUANTUM_LINK.get()), "moved stack must keep the link");
        helper.assertTrue(inInventory.getCount() == 40, "moved stack must show the pool");

        inInventory.shrink(2);
        helper.assertTrue(network.windowB().getCount() == 38, "other window must sync to 38");
        helper.succeed();
    }
}
