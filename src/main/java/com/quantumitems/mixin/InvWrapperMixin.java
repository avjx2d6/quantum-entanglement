package com.quantumitems.mixin;

import com.quantumitems.engine.WindowSlotOps;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The capability wrapper every vanilla container exposes — this is the path
 * hoppers, chutes, pipes and funnels actually take. The window rules live in
 * {@link WindowSlotOps}, shared with {@link SidedInvWrapperMixin}; there are no
 * face restrictions here, so both hooks delegate straight through.
 */
@Mixin(value = InvWrapper.class, remap = false)
public abstract class InvWrapperMixin {

    @Shadow
    public abstract Container getInv();

    @Shadow
    public abstract int getSlotLimit(int slot);

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$extractFromWindow(int slot, int amount, boolean simulate,
                                                CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = WindowSlotOps.extract(getInv(), slot, amount, simulate);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$insertIntoWindow(int slot, ItemStack stack, boolean simulate,
                                               CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = WindowSlotOps.insert(getInv(), slot, stack, simulate, getSlotLimit(slot));
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
