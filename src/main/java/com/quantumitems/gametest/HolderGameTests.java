package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The wireless-redstone core: when the pool changes REMOTELY, the container
 * holding a sibling window must be marked changed — that is what persists the
 * fresh count and fires comparators reading the container. Holders are tracked
 * at every reconcile touchpoint (container open, sweeps, hopper/menu paths).
 */
@PrefixGameTestTemplate(false)
public class HolderGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(HolderGameTests.class);
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

    /**
     * A window sits in a chest a player has opened (the open reconciles and
     * registers the holder). A REMOTE pool change — absorbing plain into the
     * OTHER window — must call setChanged() on that chest's container.
     */
    @GameTest(template = "empty", templateNamespace = "quantumitems")
    public static void remotePoolChangeMarksHolderChanged(GameTestHelper helper) {
        TestNetwork network = makeNetwork(helper, 10);
        QuantumEngine engine = QuantumEngine.onServerThread();

        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, network.windowB());
        AtomicInteger changes = new AtomicInteger();
        chest.addListener(container -> changes.incrementAndGet());

        // a real container-open registers the holder (PlayerContainerEvent.Open)
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, chest),
                Component.literal("test")));
        changes.set(0); // only remote changes from here on count

        // the REMOTE window absorbs plain elsewhere: pool 10 -> 15
        ItemStack plain = new ItemStack(Items.BREAD, 5);
        engine.absorb(network.windowA(), plain, Integer.MAX_VALUE);

        helper.assertTrue(network.windowB().getCount() == 15, "sibling stack must show 15");
        helper.assertTrue(changes.get() > 0,
                "the holder container must be marked changed by the remote pool change");
        player.closeContainer();
        player.discard();
        helper.succeed();
    }
}
