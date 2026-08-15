package com.quantumitems.mixin;

import com.quantumitems.engine.WindowSlotOps;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The sided-container counterpart of {@link InvWrapperMixin}. Furnaces, brewing
 * stands, composters and any {@link WorldlyContainer} machine expose their slots
 * through {@code SidedInvWrapper}. Same window rules ({@link WindowSlotOps}),
 * plus the two things that are genuinely sided: the slot has to be resolved for
 * the face, and the face itself may refuse the item.
 */
@Mixin(value = SidedInvWrapper.class, remap = false)
public abstract class SidedInvWrapperMixin {

    @Shadow
    @Final
    protected WorldlyContainer inv;

    @Shadow
    @Final
    protected Direction side;

    @Shadow
    public abstract int getSlotLimit(int slot);

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$extractFromWindow(int slot, int amount, boolean simulate,
                                                CallbackInfoReturnable<ItemStack> cir) {
        int slot1 = SidedInvWrapper.getSlot(inv, slot, side);
        if (slot1 == -1) {
            return;
        }
        if (side != null && !inv.canTakeItemThroughFace(slot1, inv.getItem(slot1), side)) {
            return; // the face refuses — vanilla returns EMPTY anyway
        }
        ItemStack result = WindowSlotOps.extract(inv, slot1, amount, simulate);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$insertIntoWindow(int slot, ItemStack stack, boolean simulate,
                                               CallbackInfoReturnable<ItemStack> cir) {
        int slot1 = SidedInvWrapper.getSlot(inv, slot, side);
        if (slot1 == -1) {
            return;
        }
        if (!stack.isEmpty() && !inv.canPlaceItemThroughFace(slot1, stack, side)) {
            return; // the face rejects it — vanilla would refuse too
        }
        ItemStack result = WindowSlotOps.insert(inv, slot1, stack, simulate, getSlotLimit(slot));
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
