package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two (and a half) choke points of the quantum engine. Every vanilla
 * count mutation funnels through {@code setCount} (shrink/grow/consume are
 * thin wrappers), every partial extraction through {@code split}, and
 * {@code copyAndClear} is a whole-stack move. All hooks are no-ops unless
 * the stack carries QuantumLinkData and we are on the server thread.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "setCount", at = @At("HEAD"), cancellable = true)
    private void quantumitems$setCount(int newCount, CallbackInfo ci) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (!self.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        if (engine.handleSetCount(self, newCount)) {
            ci.cancel();
        }
    }

    @Inject(method = "split", at = @At("HEAD"), cancellable = true)
    private void quantumitems$split(int amount, CallbackInfoReturnable<ItemStack> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (!self.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        ItemStack result = engine.handleSplit(self, amount);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "copyAndClear", at = @At("HEAD"), cancellable = true)
    private void quantumitems$copyAndClear(CallbackInfoReturnable<ItemStack> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (!self.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        ItemStack result = engine.handleCopyAndClear(self);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
