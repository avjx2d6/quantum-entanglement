package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The capability wrapper every vanilla container exposes. Its {@code insertItem}
 * merges by copying the <em>incoming</em> stack, growing the copy by the slot's
 * count and replacing the slot with it — so pushing plain into a slot that
 * holds a window would overwrite the window with a plain stack, orphaning the
 * pool and bypassing the {@code setCount} hook entirely.
 *
 * <p>Intercepting here makes "push plain into a window" work for every transport
 * that inserts through the capability (hoppers, chutes, pipes): the plain items
 * flow into the pool instead, and the window stays a window.
 */
@Mixin(value = InvWrapper.class, remap = false)
public abstract class InvWrapperMixin {

    @Shadow
    public abstract Container getInv();

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$insertIntoWindow(int slot, ItemStack stack, boolean simulate,
                                               CallbackInfoReturnable<ItemStack> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || stack.isEmpty() || stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return; // only plain items flowing into a window slot are special
        }
        ItemStack inSlot = getInv().getItem(slot);
        if (!inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
            return; // ordinary slot: let vanilla insertion run
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
        ItemStack remainder = stack.copy();
        engine.absorb(inSlot, remainder, room); // grows the pool, shrinks the remainder
        cir.setReturnValue(remainder.isEmpty() ? ItemStack.EMPTY : remainder);
    }
}
