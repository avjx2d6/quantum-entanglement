package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Rule 1: a window may never exist as a free item on the ground. The moment one
 * would enter the world as an ItemEntity it collapses to plain, cashing out the
 * whole pool at that stack (siblings wiped, network ended) — conserving items,
 * never leaving a linked item lying around to desync or dupe.
 */
@PrefixGameTestTemplate(false)
public class GroundGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(GroundGameTests.class);
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

    private static ItemEntity drop(GameTestHelper helper, ItemStack stack, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        ItemEntity entity = new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, stack);
        entity.setPickUpDelay(40);
        helper.getLevel().addFreshEntity(entity);
        return entity;
    }

    /** A window dropped on the ground cashes out to plain — no linked item entity survives. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public static void droppedWindowBecomesPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        ItemEntity entity = drop(helper, network.windowA(), new BlockPos(1, 2, 1));

        helper.runAfterDelay(3, () -> {
            ItemStack onGround = entity.getItem();
            helper.assertTrue(!onGround.has(ModRegistry.QUANTUM_LINK.get()),
                    "the dropped window must cash out to plain, not stay linked");
            helper.assertTrue(onGround.getCount() == 6, "all 6 pooled items become plain");
            helper.assertTrue(networks(helper).network(network.id()) == null,
                    "the network ends when it hits the ground");
            helper.assertTrue(network.windowB().isEmpty(), "the sibling window is cashed out too");
            helper.succeed();
        });
    }

    /**
     * Dropper path (Carpet-Shadow needed a dedicated DropperBlockMixin here): a
     * dropper ejecting from a window yields plain items; ejecting the LAST
     * pooled item must also end as plain on the ground with the network cashed
     * out — never a linked item entity.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void dropperEjectsPlainIncludingLastItem(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 2);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.DROPPER);
        net.minecraft.world.level.block.entity.DropperBlockEntity dropper =
                (net.minecraft.world.level.block.entity.DropperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        dropper.setItem(0, network.windowA());

        helper.pulseRedstone(new BlockPos(1, 2, 1), 4);
        helper.runAfterDelay(20, () -> helper.pulseRedstone(new BlockPos(1, 2, 1), 4));

        helper.runAfterDelay(60, () -> {
            int plain = 0;
            for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds().inflate(2.0))) {
                helper.assertTrue(!e.getItem().has(ModRegistry.QUANTUM_LINK.get()),
                        "no linked item may leave a dropper");
                plain += e.getItem().getCount();
            }
            helper.assertTrue(plain == 2, "both pooled items ejected as plain, nothing lost");
            helper.assertTrue(dropper.getItem(0).isEmpty(), "dropper emptied");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network ended with the pool");
            helper.succeed();
        });
    }

    /**
     * Dropper pushing INTO a container (NeoForge dropperInsertHook) goes
     * through copy().split(1) — at pool==1 that whole-take would ship the
     * WINDOW into the target container. Rule 2: the last item must arrive
     * plain and the network end.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public static void dropperIntoContainerLastItemArrivesPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 1);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.DROPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DropperBlock.FACING, net.minecraft.core.Direction.UP));
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
        net.minecraft.world.level.block.entity.DropperBlockEntity dropper =
                (net.minecraft.world.level.block.entity.DropperBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
        dropper.setItem(0, network.windowA());

        helper.pulseRedstone(new BlockPos(2, 1, 1), 4);

        helper.runAfterDelay(30, () -> {
            int bread = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                helper.assertTrue(!stack.has(ModRegistry.QUANTUM_LINK.get()),
                        "no linked stack may land in the chest");
                bread += stack.getCount();
            }
            helper.assertTrue(bread == 1, "the single pooled item arrives as one plain item");
            helper.assertTrue(dropper.getItem(0).isEmpty(), "dropper emptied");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network ended with its last item");
            helper.succeed();
        });
    }

    /**
     * Durability (and any other component mutation) is a property change that
     * must collapse the network IMMEDIATELY, not lazily at the next touch —
     * a lazily-diverged window is a desync window. setDamageValue routes
     * through ItemStack.set(), the one choke point for all component writes.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void damagingWindowCollapsesImmediately(GameTestHelper helper) {
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack plain = new ItemStack(Items.IRON_PICKAXE);
        int id = networks.createNetwork(plain);
        ItemStack windowA = plain.copy();
        windowA.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));
        ItemStack windowB = plain.copy();
        windowB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 2));
        engine.adopt(windowA);
        engine.adopt(windowB);

        windowA.setDamageValue(10); // took durability damage

        helper.assertTrue(networks.network(id) == null, "network must collapse the moment properties change");
        helper.assertTrue(!windowA.has(ModRegistry.QUANTUM_LINK.get()), "damaged stack leaves as plain");
        helper.assertTrue(windowA.getDamageValue() == 10, "the damage itself must apply");
        helper.assertTrue(windowB.isEmpty(), "sibling wiped by the collapse");
        helper.succeed();
    }

    /**
     * ItemStack.matches drives change detection (menu broadcastChanges, hand
     * re-equip animation, equipment sync) by comparing the live stack with a
     * remembered COPY. A window vs its own copy must read UNCHANGED — the
     * alias merge guard must not leak here, or the server resends the slot
     * every tick and the held item twitches forever. And a window vs a plain
     * stack at the same count must read CHANGED, or a same-count collapse
     * never reaches the client.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void matchesTreatsCopyAsUnchangedAndCollapseAsChanged(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);

        ItemStack remembered = network.windowA().copy(); // what lastSlots holds
        helper.assertTrue(ItemStack.matches(network.windowA(), remembered),
                "a window and its remembered copy are the same state — no per-tick resync");

        ItemStack collapsedLook = network.windowA().copy();
        collapsedLook.remove(ModRegistry.QUANTUM_LINK.get()); // collapse keeps the count
        helper.assertTrue(!ItemStack.matches(network.windowA(), collapsedLook),
                "losing the link at the same count IS a change — the client must be resynced");
        helper.succeed();
    }

    /**
     * Two live instances of the SAME member (creative clone, /give copy, a
     * hook-bypassing stack copy) must never merge with each other — vanilla
     * sees them component-equal and a click/hopper merge would inflate the
     * visible count far past the pool.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void sameMemberInstancesNeverMerge(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        net.minecraft.world.entity.player.Player player =
                helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.SimpleContainer chest = new net.minecraft.world.SimpleContainer(27);
        chest.setItem(0, network.windowA());
        net.minecraft.world.inventory.ChestMenu menu =
                net.minecraft.world.inventory.ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.setCarried(network.windowA().copy()); // a stray alias of the same member

        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);

        helper.assertTrue(chest.getItem(0).getCount() <= 6,
                "slot count must never exceed the pool (no alias merge inflation)");
        helper.assertTrue(menu.getCarried().getCount() <= 6,
                "carried count must never exceed the pool");
        helper.assertTrue(networks(helper).network(network.id()).pool == 6, "pool untouched");
        helper.succeed();
    }

    /**
     * Container-break path (Carpet-Shadow needed ItemScattererMixin; their
     * shulker case is a known issue): breaking a chest that holds a window
     * scatters PLAIN items — the pool is cashed out, siblings wiped, count
     * conserved.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public static void brokenContainerScattersPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        chest.setItem(0, network.windowA());

        helper.destroyBlock(new BlockPos(1, 1, 1));

        helper.runAfterDelay(10, () -> {
            int bread = 0;
            for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds().inflate(2.0))) {
                helper.assertTrue(!e.getItem().has(ModRegistry.QUANTUM_LINK.get()),
                        "no linked item may scatter from a broken container");
                if (e.getItem().is(Items.BREAD)) {
                    bread += e.getItem().getCount();
                }
            }
            helper.assertTrue(bread == 6, "all 6 pooled items scatter as plain, nothing lost");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network cashed out");
            helper.assertTrue(network.windowB().isEmpty(), "sibling wiped");
            helper.succeed();
        });
    }

    /**
     * The real item-loss reproduction: a window on the ground plus plain thrown
     * onto it. With cash-out there is no ground window, the items are plain, and
     * they merge with no loss (previously the first plains vanished into a
     * non-canonical ground window whose pool write was swallowed).
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public static void droppedWindowPlusPlainLosesNothing(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        drop(helper, network.windowA(), new BlockPos(1, 2, 1));
        drop(helper, new ItemStack(Items.BREAD, 3), new BlockPos(1, 2, 1));

        helper.runAfterDelay(20, () -> {
            int plain = 0;
            for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds().inflate(2.0))) {
                helper.assertTrue(!e.getItem().has(ModRegistry.QUANTUM_LINK.get()),
                        "no linked item may lie on the ground");
                plain += e.getItem().getCount();
            }
            helper.assertTrue(plain == 9, "6 pooled + 3 thrown = 9 plain, nothing lost");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network ended");
            helper.succeed();
        });
    }
}
