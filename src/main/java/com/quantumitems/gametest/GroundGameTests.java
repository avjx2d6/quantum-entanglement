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
