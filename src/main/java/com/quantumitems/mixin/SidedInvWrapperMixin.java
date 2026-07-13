package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
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
 * stands and any {@link WorldlyContainer} machine expose their slots through
 * {@code SidedInvWrapper}, whose {@code insertItem} has the same overwrite bug:
 * a merge replaces the slot with a copy of the incoming stack, erasing a window
 * that lives there. So feeding plain into a window in, say, a furnace input
 * slot would destroy the link. Absorb the plain into the pool instead, honouring
 * the same face/placement checks vanilla would.
 */
@Mixin(value = SidedInvWrapper.class, remap = false)
public abstract class SidedInvWrapperMixin {

    @Shadow
    @Final
    protected WorldlyContainer inv;

    @Shadow
    @Final
    protected Direction side;

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$insertIntoWindow(int slot, ItemStack stack, boolean simulate,
                                               CallbackInfoReturnable<ItemStack> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || stack.isEmpty() || stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return; // only plain items flowing into a window slot are special
        }
        int slot1 = SidedInvWrapper.getSlot(inv, slot, side);
        if (slot1 == -1) {
            return;
        }
        ItemStack inSlot = inv.getItem(slot1);
        if (!inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
            return; // ordinary slot: let vanilla insertion run
        }
        if (!inv.canPlaceItemThroughFace(slot1, stack, side) || !inv.canPlaceItem(slot1, stack)) {
            return; // the face rejects it — vanilla would refuse too
        }
        if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
            return; // wiped/collapsed in place — it is plain now, vanilla handles it
        }
        int room = Math.min(stack.getCount(), inSlot.getMaxStackSize() - inSlot.getCount());
        if (room <= 0) {
            cir.setReturnValue(stack); // pool is full: nothing inserted
            return;
        }
        if (simulate) {
            cir.setReturnValue(stack.getCount() > room ? stack.copyWithCount(stack.getCount() - room) : ItemStack.EMPTY);
            return;
        }
        engine.trackHolder(inSlot, inv); // before absorb: the push must notify this holder too
        ItemStack remainder = stack.copy();
        if (engine.absorb(inSlot, remainder, room) > 0) {
            inv.setChanged(); // vanilla insertion would have marked the target changed
        }
        cir.setReturnValue(remainder.isEmpty() ? ItemStack.EMPTY : remainder);
    }
}
