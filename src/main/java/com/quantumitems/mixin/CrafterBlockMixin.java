package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumLinkData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * The Crafter block, which is vanilla's own autocrafter and therefore vanilla's
 * own version of the doubled-grid duplication.
 *
 * <p>{@code CrafterBlock.dispenseFrom} builds a CraftingInput out of its nine
 * slots, assembles the recipe, dispenses the result and only then walks the
 * slots calling {@code shrink(1)} on each. Every window shows the pool, so
 * three windows of a network holding one plank read as three planks, the slabs
 * are dispensed, and the shrinks that follow can only take the one plank that
 * exists. Same fault as the crafting table, reached by a different road: this
 * one never touches {@code slotChangedCraftingGrid}.
 *
 * <p>Same rule, then — at most one window of a network in the grid — and the
 * craft is refused outright when it is broken. Vanilla's own "nothing to craft"
 * feedback (level event 1050) is used, so the block fails the way a player
 * already knows a Crafter fails.
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void quantumitems$refuseDoubledWindows(BlockState state, ServerLevel level, BlockPos pos,
                                                   CallbackInfo ci) {
        if (!(level.getBlockEntity(pos) instanceof Container crafter) || !hasDoubledNetwork(crafter)) {
            return;
        }
        level.levelEvent(1050, pos, 0);
        ci.cancel();
    }

    private static boolean hasDoubledNetwork(Container crafter) {
        Set<Integer> seen = null;
        for (int slot = 0; slot < crafter.getContainerSize(); slot++) {
            QuantumLinkData link = crafter.getItem(slot).get(ModRegistry.QUANTUM_LINK.get());
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
