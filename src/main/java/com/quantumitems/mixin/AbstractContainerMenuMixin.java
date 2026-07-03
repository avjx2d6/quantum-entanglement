package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A container click acts on the carried stack, but that instance is not always
 * the one the engine has registered as canonical: a client round-trip can hand
 * the server a fresh window instance for the cursor. When the click then writes
 * its count (a deposit shrinks it), {@code handleSetCount} sees a different live
 * canonical and treats the write as a throwaway simulation copy — leaving the
 * pool untouched and duplicating the deposited items.
 *
 * <p>Reconciling the carried window at the head of every click adopts it as the
 * canonical instance first (last touch wins), so the subsequent count change is
 * applied to the pool honestly.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"))
    private void quantumitems$reconcileCarried(int slotId, int button, ClickType clickType, Player player,
                                               CallbackInfo ci) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        ItemStack carried = ((AbstractContainerMenu) (Object) this).getCarried();
        if (carried.has(ModRegistry.QUANTUM_LINK.get())) {
            engine.reconcile(carried);
        }
    }
}
