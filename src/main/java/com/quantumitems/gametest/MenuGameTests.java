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

    /**
     * A carried window absorbs a matching plain stack into the pool even when
     * the carried instance is not the registered canonical one (a client
     * round-trip can hand the server a fresh window instance). Reconciling the
     * carried stack at click head adopts it as canonical, so the absorb grows
     * the pool honestly instead of being mistaken for a throwaway copy — no dupe.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void absorbCarriedWindowNoDupeNonCanonical(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 40);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 60)); // pool has room for only 24
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA().copy()); // a non-canonical instance of the window

        menu.clicked(0, 0, ClickType.PICKUP, player); // left click window onto the plain 60

        int pool = networks(helper).network(network.id()).pool;
        int slotCount = chest.getItem(0).getCount();
        helper.assertTrue(pool == 64, "pool must fill to the cap by absorbing 24");
        helper.assertTrue(slotCount == 36, "the slot keeps the 36 that did not fit");
        helper.assertTrue(pool + slotCount == 100, "no items conjured — 40 pooled + 60 plain, conserved");
        helper.assertTrue(network.windowB().getCount() == 64, "the sibling reflects the grown pool");
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
     * Left click with a carried window onto a matching plain stack: the slot's
     * items flow into the pool and the WINDOW IS LAID DOWN into the slot —
     * vanilla "stacks combine and land" feel. The cursor empties.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowLeftClickAbsorbsAndLands(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 20));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 0, ClickType.PICKUP, player);

        ItemStack landed = chest.getItem(0);
        helper.assertTrue(landed.has(ModRegistry.QUANTUM_LINK.get()), "the window must land in the slot");
        helper.assertTrue(landed.getCount() == 30, "landed window must show pool 30");
        helper.assertTrue(menu.getCarried().isEmpty(), "cursor must be empty after laying down");
        helper.assertTrue(network.windowB().getCount() == 30, "other window must show pool 30");
        helper.assertTrue(networks(helper).network(network.id()).pool == 30, "pool must be 30");
        helper.succeed();
    }

    /**
     * Absorption caps at the max stack size and leaves the overflow behind — the
     * network survives even when the plain stack holds more than the pool can
     * take. (A window is never poured out and never dissolves from a click.)
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowAbsorbCapsAtMax(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 60);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 40)); // only 4 fit into the pool
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(menu.getCarried().getCount() == 64, "window must fill to its cap");
        helper.assertTrue(chest.getItem(0).getCount() == 36, "the slot keeps the overflow");
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
     * Right-clicking a carried window onto a matching PLAIN stack deposits ONE
     * plain item into the slot (vanilla place-one), keeping the window and the
     * rest of the pool on the cursor. Repeats build the slot up: 11, 12, …
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowRightClickDepositsOne(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 1, ClickType.PICKUP, player); // right click window onto plain
        helper.assertTrue(chest.getItem(0).getCount() == 11, "slot must gain exactly one");
        helper.assertTrue(!chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get()), "slot stays plain");
        helper.assertTrue(menu.getCarried().getCount() == 29, "window must show pool 29");

        menu.clicked(0, 1, ClickType.PICKUP, player); // again: one more out of the pool
        helper.assertTrue(chest.getItem(0).getCount() == 12, "slot must gain another");
        helper.assertTrue(menu.getCarried().has(ModRegistry.QUANTUM_LINK.get()), "cursor keeps the window");
        helper.assertTrue(menu.getCarried().getCount() == 28, "window must show pool 28");
        helper.assertTrue(networks(helper).network(network.id()).pool == 28, "pool must be 28");
        helper.succeed();
    }

    /** Right-click deposit of the LAST pooled item: the item lands plain, the network ends. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void carriedWindowRightClickDepositsLastEndsNetwork(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(0, 1, ClickType.PICKUP, player);

        helper.assertTrue(chest.getItem(0).getCount() == 11, "the last item lands in the slot");
        helper.assertTrue(!chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get()), "slot stays plain");
        helper.assertTrue(menu.getCarried().isEmpty(), "cursor empties with the pool");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network ends honestly");
        helper.assertTrue(network.windowB().isEmpty(), "sibling wiped");
        helper.succeed();
    }

    /**
     * Drag with a carried window distributes PLAIN items extracted from the
     * pool, exactly like vanilla drag distributes a plain stack. Draining the
     * whole pool ends the network honestly (full extraction).
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void dragDrainingWholePoolEndsNetwork(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(-999, 0, ClickType.QUICK_CRAFT, player); // start left-drag
        menu.clicked(0, 1, ClickType.QUICK_CRAFT, player);    // over slot 0
        menu.clicked(1, 1, ClickType.QUICK_CRAFT, player);    // over slot 1
        menu.clicked(-999, 2, ClickType.QUICK_CRAFT, player); // release

        helper.assertTrue(!chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get()),
                "distributed stacks must be plain, not linked copies");
        helper.assertTrue(!chest.getItem(1).has(ModRegistry.QUANTUM_LINK.get()),
                "distributed stacks must be plain, not linked copies");
        helper.assertTrue(chest.getItem(0).getCount() == 3 && chest.getItem(1).getCount() == 3,
                "the 6 pooled items split evenly, nothing lost");
        helper.assertTrue(!menu.getCarried().has(ModRegistry.QUANTUM_LINK.get()),
                "no linked remainder on the cursor");
        helper.assertTrue(networks(helper).network(network.id()) == null,
                "pool fully extracted -> network ends");
        helper.assertTrue(network.windowB().isEmpty(), "sibling emptied with the pool");
        helper.succeed();
    }

    /**
     * A drag that leaves part of the pool keeps the window (and the network)
     * on the cursor — dragging is just multiple extractions, never a collapse
     * (regression: a slightly-wiggly right click used to destroy the network).
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void dragPartialKeepsWindowOnCursor(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 8);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(-999, 0, ClickType.QUICK_CRAFT, player); // start left-drag
        menu.clicked(0, 1, ClickType.QUICK_CRAFT, player);
        menu.clicked(1, 1, ClickType.QUICK_CRAFT, player);
        menu.clicked(2, 1, ClickType.QUICK_CRAFT, player);    // three slots: share = 8/3 = 2
        menu.clicked(-999, 2, ClickType.QUICK_CRAFT, player); // release

        for (int slot = 0; slot < 3; slot++) {
            helper.assertTrue(chest.getItem(slot).getCount() == 2, "each slot gets its share of 2");
            helper.assertTrue(!chest.getItem(slot).has(ModRegistry.QUANTUM_LINK.get()), "shares are plain");
        }
        ItemStack carried = menu.getCarried();
        helper.assertTrue(carried.has(ModRegistry.QUANTUM_LINK.get()), "the window stays on the cursor");
        helper.assertTrue(carried.getCount() == 2, "window shows the remaining pool 2");
        helper.assertTrue(networks(helper).network(network.id()).pool == 2, "pool must be 2");
        helper.assertTrue(network.windowB().getCount() == 2, "sibling tracks the pool");
        helper.succeed();
    }

    /** Right-drag places one plain per slot, window stays with the rest. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void rightDragPlacesOnePlainPerSlot(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 5);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA());

        menu.clicked(-999, 4, ClickType.QUICK_CRAFT, player); // start right-drag (type 1)
        menu.clicked(0, 5, ClickType.QUICK_CRAFT, player);
        menu.clicked(1, 5, ClickType.QUICK_CRAFT, player);
        menu.clicked(-999, 6, ClickType.QUICK_CRAFT, player); // release

        helper.assertTrue(chest.getItem(0).getCount() == 1 && chest.getItem(1).getCount() == 1,
                "one plain item lands in each dragged slot");
        helper.assertTrue(!chest.getItem(0).has(ModRegistry.QUANTUM_LINK.get())
                && !chest.getItem(1).has(ModRegistry.QUANTUM_LINK.get()), "placed items are plain");
        helper.assertTrue(menu.getCarried().has(ModRegistry.QUANTUM_LINK.get()), "window stays carried");
        helper.assertTrue(menu.getCarried().getCount() == 3, "window shows the remaining pool 3");
        helper.assertTrue(networks(helper).network(network.id()).pool == 3, "pool must be 3");
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

    /**
     * Shift-clicking a plain stack merges it into a matching window elsewhere,
     * feeding the pool — the generic stacking hook makes quick-move treat a
     * window as a valid merge target, no click-specific code.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void shiftClickPlainMergesIntoWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 30);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, network.windowA()); // window waiting in the hotbar
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player); // shift-click the plain out of the chest

        helper.assertTrue(chest.getItem(0).isEmpty(), "the plain stack must leave the chest");
        helper.assertTrue(network.windowA().getCount() == 40, "it must merge into the window's pool");
        helper.assertTrue(network.windowB().getCount() == 40, "other window tracks the pool");
        helper.assertTrue(networks(helper).network(network.id()).pool == 40, "pool must be 40");
        helper.succeed();
    }

    /**
     * Rule 4: shift-clicking a window while the destination holds a MATCHING
     * PARTIAL plain stack must not merge-drain the pool into it (that dissolved
     * the network). With an empty slot available the window relocates whole,
     * link intact; the plain stack is left untouched.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void shiftClickWindowPrefersEmptySlotOverMerge(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(Items.BREAD, 10)); // matching partial plain
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);

        helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
        int plainTens = 0;
        ItemStack movedWindow = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
                movedWindow = stack;
            } else if (!stack.isEmpty()) {
                helper.assertTrue(stack.getCount() == 10, "the partial plain stack must be untouched");
                plainTens++;
            }
        }
        helper.assertTrue(plainTens == 1, "exactly the one untouched plain stack");
        helper.assertTrue(!movedWindow.isEmpty(), "the window must relocate whole into an empty slot");
        helper.assertTrue(movedWindow.getCount() == 6, "window still shows the pool");
        helper.assertTrue(networks(helper).network(network.id()) != null, "network must survive the shift-click");
        helper.assertTrue(networks(helper).network(network.id()).pool == 6, "pool unchanged");
        helper.assertTrue(network.windowB().getCount() == 6, "sibling window unchanged");
        helper.succeed();
    }

    /**
     * Rule 4, no-empty-slot arm: when the only way a shift-clicked window could
     * move is merging into existing plain stacks, it collapses to plain first —
     * predictable, and every item is conserved (10 + 6 = 16).
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void shiftClickWindowNoRoomCollapsesToPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.STICK, 64)); // no empty slots
        }
        player.getInventory().setItem(0, new ItemStack(Items.BREAD, 10)); // the only landing spot
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);

        helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
        ItemStack landing = player.getInventory().getItem(0);
        helper.assertTrue(!landing.has(ModRegistry.QUANTUM_LINK.get()), "landing stack stays plain");
        helper.assertTrue(landing.getCount() == 16, "10 + 6 pooled items, all conserved");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network cashed out");
        helper.assertTrue(network.windowB().isEmpty(), "sibling wiped by the cash-out");
        helper.succeed();
    }

    /**
     * A pool of one must still be a real window you can carry: picking up a
     * single-item window keeps the link (whole-stack take relocates it), so
     * you can hold one linked item in the cursor just like two or more.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void cursorHoldsSingleLinkedItem(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowA());
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.PICKUP, player); // pick the single-item window up

        ItemStack carried = menu.getCarried();
        helper.assertTrue(carried.has(ModRegistry.QUANTUM_LINK.get()), "cursor must hold a linked item, not plain");
        helper.assertTrue(carried.getCount() == 1, "the single pooled item");
        helper.assertTrue(chest.getItem(0).isEmpty(), "chest slot must be empty");
        helper.assertTrue(networks(helper).network(network.id()).pool == 1, "pool stays 1");

        // place it back down — still a live window, other member still tracks it
        menu.clicked(3, 0, ClickType.PICKUP, player);
        ItemStack placed = chest.getItem(3);
        helper.assertTrue(placed.has(ModRegistry.QUANTUM_LINK.get()), "placed stack keeps the link");
        helper.assertTrue(network.windowB().getCount() == 1, "other window still shows 1");
        helper.succeed();
    }
}
