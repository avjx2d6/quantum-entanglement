package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bundle insertion is already an honest extraction for a window in two of its
 * three paths: merging into a matching inner stack copies the INNER (plain)
 * stack and shrinks the window (pool debited through the setCount hook), and a
 * partial {@code split} yields plain. The remaining path — the whole stack
 * fits and {@code split(count)} would relocate the WINDOW ITSELF into the
 * bundle's component data — would bury a live member inside item NBT,
 * invisible to sweeps, with its items lost when the husk is next reconciled.
 * Cash it out first: inserting your whole window into a bundle is a full
 * extraction, the bundle receives plain, the network ends, items conserved.
 */
@Mixin(BundleContents.Mutable.class)
public abstract class BundleContentsMutableMixin {

    @Invoker("getMaxAmountToAdd")
    abstract int quantumitems$maxAmountToAdd(ItemStack stack);

    @Inject(method = "tryInsert", at = @At("HEAD"))
    private void quantumitems$wholeInsertCashesOut(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (stack.isEmpty() || !stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        if (quantumitems$maxAmountToAdd(stack) < stack.getCount()) {
            return; // partial insert: split -> plain, already honest
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null) {
            engine.cashOutToPlain(stack);
        } else {
            stack.remove(ModRegistry.QUANTUM_LINK.get()); // client prediction mirror
        }
    }
}
