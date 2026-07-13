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
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;
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
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        QuantumCommand.register(event.getDispatcher());
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
        sweepPlayer(engine, event.getEntity());
    }

    /**
     * Slow safety-net sweep: transient linked copies are deliberately left
     * alone by the engine, so a copy that somehow materializes in a player
     * inventory (creative clone, /give, mod quirks) is caught here.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 0) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        for (Player player : event.getServer().getPlayerList().getPlayers()) {
            sweepPlayer(engine, player);
        }
    }

    public static void sweepPlayer(QuantumEngine engine, Player player) {
        Set<Long> seen = new HashSet<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            reconcileSlot(engine, inventory, slot, seen);
        }
        Container enderChest = player.getEnderChestInventory();
        for (int slot = 0; slot < enderChest.getContainerSize(); slot++) {
            reconcileSlot(engine, enderChest, slot, seen);
        }
    }

    /** Reconcile everything the player is about to see when a container opens. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (Slot slot : event.getContainer().slots) {
            ItemStack stack = slot.getItem();
            if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
                engine.reconcileScan(stack, seen);
                if (stack.isEmpty()) {
                    slot.set(ItemStack.EMPTY);
                } else {
                    engine.trackHolder(stack, slot.container);
                }
            }
        }
    }

    /**
     * Rule 1: a window may never exist as a free item. The instant one would
     * appear on the ground it cashes out to plain (whole pool, siblings wiped,
     * network ended); a dead husk is discarded. No linked item ever lies in the
     * world, so the ground-desync and item-loss that came with it are gone.
     */
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
            engine.cashOutToPlain(stack);
            if (stack.isEmpty()) {
                event.setCanceled(true); // dead husk: nothing to drop
            } else {
                itemEntity.setItem(stack); // push the cashed-out plain to the entity's synced data
            }
        }
        // Windows INSIDE container items (a broken shulker's component data,
        // backpack NBT) are deliberately left alone: a sleeping window is the
        // same situation as a window in an unloaded chunk — no live instance,
        // possibly stale count, reconciled honestly at the next touch after it
        // re-materializes. The pool authority makes sleeping safe: it can never
        // dupe, and if the network ends while it sleeps, it wakes up empty
        // because its items were already cashed out elsewhere.
    }

    /**
     * Pickup handling. Linked windows go through the vanilla path — the
     * Inventory.add mixin transfers them wholesale, and vanilla's own
     * pickup-delay/owner checks apply (no instant re-pickup of drops).
     * Plain items get one extra step: if the player carries a window of a
     * matching network, they are absorbed into the pool first.
     */
    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || event.getItemEntity().hasPickUpDelay()) {
            return;
        }
        ItemStack stack = event.getItemEntity().getItem();
        if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return; // windows: vanilla path + Inventory.add mixin
        }
        int absorbed = engine.absorbPickup(event.getPlayer().getInventory(), stack);
        if (absorbed > 0 && stack.isEmpty()) {
            event.setCanPickup(TriState.FALSE);
            event.getPlayer().take(event.getItemEntity(), absorbed);
            event.getItemEntity().discard();
        }
        // leftover falls through to the vanilla pickup (partial stacks, free slots)
    }

    /** Renaming on an anvil is a property change: the network collapses instantly. */
    @SubscribeEvent
    public static void onAnvilRepair(AnvilRepairEvent event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null && event.getOutput().has(ModRegistry.QUANTUM_LINK.get())) {
            engine.reconcile(event.getOutput()); // diverged components → collapse
        }
    }

    /** Enchanting mutates the stack in place — same collapse, instantly. */
    @SubscribeEvent
    public static void onEnchant(PlayerEnchantItemEvent event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null && event.getEnchantedItem().has(ModRegistry.QUANTUM_LINK.get())) {
            engine.reconcile(event.getEnchantedItem());
        }
    }

    /** Guard: crafting with the last pooled item must not dupe it (see engine docs). */
    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        Container grid = event.getInventory();
        for (int slot = 0; slot < grid.getContainerSize(); slot++) {
            ItemStack stack = grid.getItem(slot);
            if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
                engine.precollapseIfSingleton(stack);
            }
        }
    }

    private static void reconcileSlot(QuantumEngine engine, Container container, int slot, Set<Long> seen) {
        ItemStack stack = container.getItem(slot);
        if (stack.has(ModRegistry.QUANTUM_LINK.get())) {
            engine.reconcileScan(stack, seen);
            if (stack.isEmpty()) {
                container.setItem(slot, ItemStack.EMPTY);
            } else {
                engine.trackHolder(stack, container);
            }
        }
    }
}
