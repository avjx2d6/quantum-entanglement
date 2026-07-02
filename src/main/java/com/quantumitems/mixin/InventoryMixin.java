package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla inserts items into the player inventory by growing a zero-count
 * copy in the slot and shrinking the source ({@code addResource}) — item
 * pickup and returning the cursor stack on menu close both go through here.
 * A quantum window must instead transfer wholesale.
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void quantumitems$add(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        if (!stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        Boolean handled = engine.handleInventoryAdd((Inventory) (Object) this, slot, stack);
        if (handled != null) {
            cir.setReturnValue(handled);
        }
    }
}
