package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumLinkData;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Two windows of the SAME network in one crafting grid.
 *
 * <p>Every window shows the pool, because that is what a window is. Put three
 * windows of a network holding two planks into a row and the grid honestly
 * displays three stacks of two — and the recipe matcher, which counts stacks,
 * reads that as three planks and offers six slabs. The craft then shrinks each
 * of the three slots by one. Extraction is bounded by the pool, so the third
 * shrink comes up empty, but the slabs were handed over before that: three
 * planks' worth of goods out of a network that owned two. Measured at a pool of
 * one it was six slabs from a single plank.
 *
 * <p>Nothing can be done about this at the extraction end — by the time items
 * are taken the result already exists. It has to be refused at the match, and
 * the rule is the narrowest one that closes it: A CRAFTING GRID MAY HOLD AT
 * MOST ONE WINDOW OF ANY GIVEN NETWORK. Two windows of different networks are
 * fine, they have separate pools. One window alongside plain items is fine, it
 * is spending its own pool once.
 *
 * <p>This is the single choke point for both vanilla grids: the crafting table
 * calls it and so does the player's own 2x2 through InventoryMenu.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {

    @Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"), cancellable = true)
    private static void quantumitems$refuseDoubledWindows(AbstractContainerMenu menu, Level level,
                                                          Player player, CraftingContainer grid,
                                                          ResultContainer result,
                                                          @Nullable RecipeHolder<CraftingRecipe> recipe,
                                                          CallbackInfo ci) {
        if (level.isClientSide || !hasDoubledNetwork(grid)) {
            return;
        }
        // The same tail vanilla runs when no recipe matches, so the client is
        // told the result is empty rather than left showing a stale one.
        result.setItem(0, ItemStack.EMPTY);
        menu.setRemoteSlot(0, ItemStack.EMPTY);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
                    menu.containerId, menu.incrementStateId(), 0, ItemStack.EMPTY));
        }
        ci.cancel();
    }

    private static boolean hasDoubledNetwork(CraftingContainer grid) {
        Set<Integer> seen = null;
        for (int slot = 0; slot < grid.getContainerSize(); slot++) {
            QuantumLinkData link = grid.getItem(slot).get(ModRegistry.QUANTUM_LINK.get());
            if (link == null) {
                continue;
            }
            if (seen == null) {
                seen = new HashSet<>();
            }
            if (!seen.add(link.networkId())) {
                return true;
            }
        }
        return false;
    }
}
