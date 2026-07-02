package com.quantumitems;

import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.common.util.TriState;

@EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        QuantumEngine.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QuantumEngine.stop();
    }

    /** Reconcile the whole inventory (plus ender chest) when a player logs in. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        Player player = event.getEntity();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            reconcileSlot(engine, inventory, slot);
        }
        Container enderChest = player.getEnderChestInventory();
        for (int slot = 0; slot < enderChest.getContainerSize(); slot++) {
            reconcileSlot(engine, enderChest, slot);
        }
    }

    /** Reconcile everything the player is about to see when a container opens. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        for (Slot slot : event.getContainer().slots) {
            ItemStack stack = slot.getItem();
            if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
                engine.reconcile(stack);
                if (stack.isEmpty()) {
                    slot.set(ItemStack.EMPTY);
                }
            }
        }
    }

    /** Reconcile linked items appearing on the ground (drops, broken containers, chunk load). */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity itemEntity)) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
            engine.reconcile(stack);
            if (stack.isEmpty()) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * Custom pickup for linked windows. Vanilla pickup copies the stack and
     * grows the copy from zero ({@code Inventory.addResource}), which the
     * canonical-instance registry would rightly treat as a duplicate — so we
     * move the window instance into a free slot wholesale instead.
     */
    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        ItemStack stack = event.getItemEntity().getItem();
        if (!stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        event.setCanPickup(TriState.FALSE);
        engine.reconcile(stack);
        if (stack.isEmpty()) {
            event.getItemEntity().discard();
            return;
        }
        Inventory inventory = event.getPlayer().getInventory();
        int freeSlot = inventory.getFreeSlot();
        if (freeSlot < 0) {
            return; // no room — the window stays on the ground
        }
        inventory.setItem(freeSlot, stack);
        event.getPlayer().take(event.getItemEntity(), stack.getCount());
        event.getItemEntity().setItem(ItemStack.EMPTY);
        event.getItemEntity().discard();
    }

    private static void reconcileSlot(QuantumEngine engine, Container container, int slot) {
        ItemStack stack = container.getItem(slot);
        if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
            engine.reconcile(stack);
            if (stack.isEmpty()) {
                container.setItem(slot, ItemStack.EMPTY);
            }
        }
    }
}
