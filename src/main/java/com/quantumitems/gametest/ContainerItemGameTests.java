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
 * Windows must never sleep inside item NBT (bundles, broken shulker boxes) —
 * a buried husk is invisible to sweeps and loses its items on the next
 * reconcile. Entering a container item is an honest extraction / cash-out.
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

    /** Breaking a shulker box with a window inside: the dropped item carries honest plain. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public static void shulkerBreakCashesOutContents(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 6);
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
            int bread = 0;
            for (ItemStack inner : contents.nonEmptyItems()) {
                helper.assertTrue(!inner.has(ModRegistry.QUANTUM_LINK.get()),
                        "no window may sleep inside the shulker item");
                bread += inner.getCount();
            }
            helper.assertTrue(bread == 6, "all 6 pooled items ride inside as plain, conserved");
            helper.assertTrue(networks(helper).network(network.id()) == null, "network cashed out");
            helper.assertTrue(network.windowB().isEmpty(), "sibling wiped");
            helper.succeed();
        });
    }
}
