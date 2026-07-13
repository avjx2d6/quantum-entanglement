package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Container items and windows. BUNDLES are merge-happy loose-item bags:
 * inserting into one is an honest extraction (plain inside, pool debited).
 * SHULKERS preserve their contents through breaking — a window inside one
 * SLEEPS: no live instance, possibly stale count, exactly like a window in an
 * unloaded chunk. The pool authority makes sleeping safe: it reconciles
 * honestly on wake-up, can never dupe, and if the network ends while it
 * sleeps it wakes up empty because its items were cashed out elsewhere.
 */
@PrefixGameTestTemplate(false)
public class ContainerItemGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(ContainerItemGameTests.class);
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

    /** Inserting a whole window into a bundle is a full extraction: plain inside, network ends. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void bundleWholeInsertCashesOut(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);

        BundleContents.Mutable bundle = new BundleContents.Mutable(BundleContents.EMPTY);
        int inserted = bundle.tryInsert(network.windowA());

        helper.assertTrue(inserted == 6, "all 6 pooled items enter the bundle");
        int stored = 0;
        for (ItemStack inner : bundle.toImmutable().items()) {
            helper.assertTrue(!inner.has(ModRegistry.QUANTUM_LINK.get()), "the bundle stores plain, never a window");
            stored += inner.getCount();
        }
        helper.assertTrue(stored == 6, "6 plain items inside, conserved");
        helper.assertTrue(networks(helper).network(network.id()) == null, "network cashed out");
        helper.assertTrue(network.windowB().isEmpty(), "sibling wiped");
        helper.succeed();
    }

    /** A partial bundle insert extracts plain and the network survives with the rest. */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void bundlePartialInsertExtractsPlain(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);

        BundleContents.Mutable bundle = new BundleContents.Mutable(BundleContents.EMPTY);
        bundle.tryInsert(new ItemStack(Items.BREAD, 60)); // room for only 4 more bread
        int inserted = bundle.tryInsert(network.windowA());

        helper.assertTrue(inserted == 4, "only 4 fit");
        for (ItemStack inner : bundle.toImmutable().items()) {
            helper.assertTrue(!inner.has(ModRegistry.QUANTUM_LINK.get()), "the bundle stores plain, never a window");
        }
        helper.assertTrue(network.windowA().has(ModRegistry.QUANTUM_LINK.get()), "window keeps its link");
        helper.assertTrue(network.windowA().getCount() == 6, "window shows pool 6");
        helper.assertTrue(networks(helper).network(network.id()).pool == 6, "pool must be 6");
        helper.assertTrue(network.windowB().getCount() == 6, "sibling tracks the pool");
        helper.succeed();
    }

    /**
     * The GC heisenbug: a woken sleeper carries a STALE count, and the hopper
     * SIMULATES extraction with copyWithCount before really extracting. When
     * the old canonical instance has been garbage-collected, that orphan copy's
     * setCount used to be adopted and its delta (new − staleSeen) applied to
     * the pool — 1−16 = −15 floored the pool to zero and dissolved the network,
     * destroying the sibling's items. A delta against a baseline that does not
     * match the pool is meaningless and must never touch it.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void staleOrphanCopyNeverDrainsPool(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 16);
        QuantumEngine engine = QuantumEngine.onServerThread();

        // the window goes to sleep (shulker broken): its canonical ref dies
        engine.deregister(network.id(), 1);
        // the pool halves while it sleeps — windowA's count 16 is stale now
        network.windowB().split(8);
        helper.assertTrue(networks(helper).network(network.id()).pool == 8, "pool must be 8");

        // wake-up: the hopper's SIMULATE path sizes an orphan linked copy
        ItemStack simulated = network.windowA().copyWithCount(1);

        helper.assertTrue(simulated.getCount() == 1, "the simulate copy gets its count");
        helper.assertTrue(networks(helper).network(network.id()) != null,
                "the network must survive an orphan copy's stale-baseline write");
        helper.assertTrue(networks(helper).network(network.id()).pool == 8, "pool untouched");
        helper.assertTrue(network.windowB().getCount() == 8, "sibling untouched");
        helper.succeed();
    }

    /**
     * A shulker box protects its contents through breaking, so it protects the
     * link too: the window SLEEPS inside the dropped item (network alive), and
     * on placement it wakes and reconciles to the CURRENT pool — even if the
     * pool changed while it slept.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public static void shulkerCarriesSleepingWindow(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
        QuantumEngine engine = QuantumEngine.onServerThread();
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.SHULKER_BOX);
        ShulkerBoxBlockEntity shulker = (ShulkerBoxBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        shulker.setItem(0, network.windowA());

        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(1, 1, 1)), true); // with drops

        helper.runAfterDelay(10, () -> {
            ItemStack shulkerItem = ItemStack.EMPTY;
            for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds().inflate(2.0))) {
                if (e.getItem().is(Items.SHULKER_BOX)) {
                    shulkerItem = e.getItem();
                }
            }
            helper.assertTrue(!shulkerItem.isEmpty(), "the shulker box must drop as an item");
            ItemContainerContents contents = shulkerItem.get(DataComponents.CONTAINER);
            helper.assertTrue(contents != null, "the dropped shulker keeps its contents");
            ItemStack sleeping = ItemStack.EMPTY;
            for (ItemStack inner : contents.nonEmptyItems()) {
                sleeping = inner;
            }
            helper.assertTrue(sleeping.has(ModRegistry.QUANTUM_LINK.get()),
                    "the window sleeps inside with its link intact");
            helper.assertTrue(networks(helper).network(network.id()) != null, "network survives the break");
            helper.assertTrue(network.windowB().getCount() == 6, "sibling keeps working");

            // the pool changes while the window sleeps: sibling absorbs 4 more
            engine.absorb(network.windowB(), new ItemStack(Items.BREAD, 4), Integer.MAX_VALUE);
            helper.assertTrue(networks(helper).network(network.id()).pool == 10, "pool is 10 now");

            // wake up: place the shulker back and touch its content
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.SHULKER_BOX);
            ShulkerBoxBlockEntity placed =
                    (ShulkerBoxBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
            placed.applyComponentsFromItemStack(shulkerItem);
            ItemStack awake = placed.getItem(0);
            helper.assertTrue(awake.has(ModRegistry.QUANTUM_LINK.get()), "the woken stack is the window");
            engine.reconcile(awake); // the first touch (open/hopper/sweep) does this
            helper.assertTrue(awake.getCount() == 10, "the stale count heals to the CURRENT pool");
            helper.succeed();
        });
    }

    /**
     * The "empty surprise" is the mechanic itself, delayed: if the pool is
     * consumed while a window sleeps in a shulker, the network ends and the
     * sleeper wakes up empty — its items already materialized elsewhere.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public static void sleepingWindowWakesEmptyAfterPoolDrained(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 5);
        QuantumEngine engine = QuantumEngine.onServerThread();
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.SHULKER_BOX);
        ShulkerBoxBlockEntity shulker = (ShulkerBoxBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        shulker.setItem(0, network.windowA());

        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(1, 1, 1)), true);

        helper.runAfterDelay(10, () -> {
            ItemStack shulkerItem = ItemStack.EMPTY;
            for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds().inflate(2.0))) {
                if (e.getItem().is(Items.SHULKER_BOX)) {
                    shulkerItem = e.getItem();
                }
            }
            helper.assertTrue(!shulkerItem.isEmpty(), "the shulker box must drop as an item");

            network.windowB().shrink(5); // the whole pool is consumed elsewhere
            helper.assertTrue(networks(helper).network(network.id()) == null, "network ends with the pool");

            helper.setBlock(new BlockPos(1, 2, 1), Blocks.SHULKER_BOX);
            ShulkerBoxBlockEntity placed =
                    (ShulkerBoxBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));
            placed.applyComponentsFromItemStack(shulkerItem);
            ItemStack awake = placed.getItem(0);
            engine.reconcile(awake); // first touch
            helper.assertTrue(awake.isEmpty(), "the sleeper wakes empty — its items were spent elsewhere");
            helper.succeed();
        });
    }
}
