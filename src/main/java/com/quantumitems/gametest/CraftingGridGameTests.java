package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Several windows OF THE SAME NETWORK in one crafting grid.
 *
 * <p>This is the arrangement where a recipe can lie about what it has. Three
 * windows of a network holding two planks each show two planks each, because
 * every window shows the pool — so the grid looks like it is holding six planks
 * when the network owns two, and a recipe that wants three of them matches.
 * Vanilla then shrinks each of the three slots by one, which is three items
 * taken out of a pool of two.
 *
 * <p>The mod's rule is that extraction is bounded by the pool, so the third
 * shrink can only come up empty — but by then the slabs are already in the
 * player's hand. Whether that ends up conjuring items is what these tests are
 * for, and the assertion is the usual one: count everything before, count
 * everything after.
 */
@PrefixGameTestTemplate(false)
public class CraftingGridGameTests {

    @EventBusSubscriber(modid = com.quantumitems.QuantumItemsMod.MOD_ID)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(CraftingGridGameTests.class);
        }
    }

    /** A network with as many windows as asked for, all sharing one pool. */
    private static ItemStack[] makeWindows(GameTestHelper helper, Item item, int pool, int windows) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(item, pool);
        int id = networks.createNetwork(plain);
        ItemStack[] out = new ItemStack[windows];
        for (int i = 0; i < windows; i++) {
            ItemStack window = plain.copy();
            // member 1 exists from createNetwork; the rest are added here
            int member = i == 0 ? 1 : networks.addMember(id);
            window.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, member));
            engine.adopt(window);
            out[i] = window;
        }
        return out;
    }

    private static int pool(GameTestHelper helper, ItemStack window) {
        QuantumLinkData link = window.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return 0;
        }
        QuantumNetworks.Network network =
                QuantumNetworks.get(helper.getLevel().getServer()).network(link.networkId());
        return network == null ? 0 : network.pool;
    }

    @SuppressWarnings("removal") // no replacement exists yet for makeMockServerPlayerInLevel
    private static ServerPlayer player(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private static CraftingMenu openTable(GameTestHelper helper, ServerPlayer player) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CRAFTING_TABLE);
        return new CraftingMenu(1, player.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(pos)));
    }

    /**
     * Every plank in the world afterwards: whatever the pool still claims, plus
     * every plain plank lying in the menu, the cursor or the player. Slabs are
     * counted separately by the caller — two of them are one plank.
     */
    private static int planksInPlay(GameTestHelper helper, CraftingMenu menu, ServerPlayer player,
                                    ItemStack anyWindow, Item plank) {
        int total = pool(helper, anyWindow);
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.is(plank) && !stack.has(ModRegistry.QUANTUM_LINK.get())) {
                total += stack.getCount();
            }
        }
        if (menu.getCarried().is(plank) && !menu.getCarried().has(ModRegistry.QUANTUM_LINK.get())) {
            total += menu.getCarried().getCount();
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(plank) && !stack.has(ModRegistry.QUANTUM_LINK.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int slabsHeld(CraftingMenu menu, ServerPlayer player) {
        int total = menu.getCarried().is(Items.OAK_SLAB) ? menu.getCarried().getCount() : 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.OAK_SLAB)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The headline case: a pool of two, three windows, a recipe that wants
     * three. Six slabs are three planks' worth, and the network only ever owned
     * two, so six slabs out of this is a duplication.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void threeWindowsOneNetworkCannotOutspendThePool(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack[] windows = makeWindows(helper, Items.OAK_PLANKS, 2, 3);
        CraftingMenu menu = openTable(helper, player);

        // slots 1..9 are the grid; a row of three planks is the slab recipe
        menu.slots.get(1).set(windows[0]);
        menu.slots.get(2).set(windows[1]);
        menu.slots.get(3).set(windows[2]);
        menu.slotsChanged(menu.slots.get(1).container);

        ItemStack result = menu.slots.get(0).getItem();
        if (result.isEmpty()) {
            helper.succeed(); // the grid refused to offer a craft it cannot pay for
            return;
        }
        menu.clicked(0, 0, ClickType.PICKUP, player);

        int slabs = slabsHeld(menu, player);
        int planks = planksInPlay(helper, menu, player, windows[0], Items.OAK_PLANKS);
        // Two slabs are worth one plank. Nothing may come out of this worth
        // more than the two planks that went in.
        helper.assertTrue(planks + slabs / 2 <= 2,
                "a pool of 2 planks produced " + slabs + " slabs with " + planks
                        + " planks left — that is " + (planks + slabs / 2) + " planks' worth");
        helper.succeed();
    }

    /**
     * The same with a pool of one, which is the case
     * {@code precollapseIfSingleton} was written for — but it runs on
     * ItemCraftedEvent, which fires after the result has been taken.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void threeWindowsOnOneItemCannotCraft(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack[] windows = makeWindows(helper, Items.OAK_PLANKS, 1, 3);
        CraftingMenu menu = openTable(helper, player);

        menu.slots.get(1).set(windows[0]);
        menu.slots.get(2).set(windows[1]);
        menu.slots.get(3).set(windows[2]);
        menu.slotsChanged(menu.slots.get(1).container);

        if (menu.slots.get(0).getItem().isEmpty()) {
            helper.succeed();
            return;
        }
        menu.clicked(0, 0, ClickType.PICKUP, player);

        int slabs = slabsHeld(menu, player);
        int planks = planksInPlay(helper, menu, player, windows[0], Items.OAK_PLANKS);
        helper.assertTrue(planks + slabs / 2 <= 1,
                "a pool of 1 plank produced " + slabs + " slabs with " + planks + " planks left");
        helper.succeed();
    }

    /**
     * Two windows of one network and a plain plank alongside — the mixed grid,
     * where the pool legitimately covers one of the three but not two.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void twoWindowsPlusPlainCannotOutspendThePool(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack[] windows = makeWindows(helper, Items.OAK_PLANKS, 1, 2);
        CraftingMenu menu = openTable(helper, player);

        menu.slots.get(1).set(windows[0]);
        menu.slots.get(2).set(windows[1]);
        menu.slots.get(3).set(new ItemStack(Items.OAK_PLANKS, 1));
        menu.slotsChanged(menu.slots.get(1).container);

        if (menu.slots.get(0).getItem().isEmpty()) {
            helper.succeed();
            return;
        }
        menu.clicked(0, 0, ClickType.PICKUP, player);

        int slabs = slabsHeld(menu, player);
        int planks = planksInPlay(helper, menu, player, windows[0], Items.OAK_PLANKS);
        // one pooled plank plus one plain one went in
        helper.assertTrue(planks + slabs / 2 <= 2,
                "2 planks went in but " + (planks + slabs / 2) + " planks' worth came out");
        helper.succeed();
    }

    /**
     * The other half of the rule, and the reason it is stated as narrowly as it
     * is: ONE window spending its own pool once is an ordinary craft and has to
     * keep working. Without this test the fix could quietly refuse every recipe
     * a window appears in and every test above would still pass.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void oneWindowStillCrafts(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack[] windows = makeWindows(helper, Items.OAK_PLANKS, 8, 2);
        CraftingMenu menu = openTable(helper, player);

        menu.slots.get(1).set(windows[0]);
        menu.slots.get(2).set(new ItemStack(Items.OAK_PLANKS, 1));
        menu.slots.get(3).set(new ItemStack(Items.OAK_PLANKS, 1));
        menu.slotsChanged(menu.slots.get(1).container);

        helper.assertTrue(!menu.slots.get(0).getItem().isEmpty(),
                "one window plus two plain planks is a legitimate slab recipe");
        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(slabsHeld(menu, player) == 6, "the craft must yield its six slabs");
        helper.assertTrue(pool(helper, windows[0]) == 7,
                "exactly one plank must come off the pool, not " + (8 - pool(helper, windows[0])));
        helper.assertTrue(windows[1].getCount() == 7, "the sibling window must show the debited pool");
        helper.succeed();
    }

    /** Two windows of DIFFERENT networks have separate pools and nothing to double-count. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void windowsOfDifferentNetworksStillCraft(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack[] first = makeWindows(helper, Items.OAK_PLANKS, 8, 1);
        ItemStack[] second = makeWindows(helper, Items.OAK_PLANKS, 8, 1);
        CraftingMenu menu = openTable(helper, player);

        menu.slots.get(1).set(first[0]);
        menu.slots.get(2).set(second[0]);
        menu.slots.get(3).set(new ItemStack(Items.OAK_PLANKS, 1));
        menu.slotsChanged(menu.slots.get(1).container);

        helper.assertTrue(!menu.slots.get(0).getItem().isEmpty(),
                "separate networks have separate pools — this craft is honest");
        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(slabsHeld(menu, player) == 6, "the craft must yield its six slabs");
        helper.assertTrue(pool(helper, first[0]) == 7 && pool(helper, second[0]) == 7,
                "one plank off each pool");
        helper.succeed();
    }
}
