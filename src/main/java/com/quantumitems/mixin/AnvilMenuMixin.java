package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The anvil's repair-material slot, which is the one place in the game that
 * consumes items by DELETING the stack instead of shrinking it.
 *
 * <p>{@code AnvilMenu.onTake} spends the material like this:
 *
 * <pre>{@code
 * if (!itemstack.isEmpty() && itemstack.getCount() > this.repairItemCountCost) {
 *     itemstack.shrink(this.repairItemCountCost);
 *     this.inputSlots.setItem(1, itemstack);
 * } else {
 *     this.inputSlots.setItem(1, ItemStack.EMPTY);
 * }
 * }</pre>
 *
 * <p>The first branch is fine: {@code shrink} is {@code setCount}, which the
 * mod already intercepts, and the pool is debited by exactly the repair cost.
 * The second branch is a hole. Nothing is shrunk and nothing is set — the stack
 * is simply replaced with nothing, so a window used up to its last item leaves
 * the pool still holding that item. The sibling window goes on offering it, and
 * the player has both the repair and the ingot. That is a duplication, which is
 * the one thing this mod exists not to do.
 *
 * <p>So when the anvil is about to take that branch, spend the material through
 * {@code setCount} first. Vanilla then clears a stack that is already empty.
 *
 * <p>The other input is not touched here. It is destroyed outright a few lines
 * later, but the AnvilRepairEvent that fires in between collapses the network
 * into the stack the player is taking — see
 * {@code ServerEvents#onAnvilRepair} — so by the time vanilla clears the slot
 * there is nothing left in it to lose.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

    @Shadow
    public int repairItemCountCost;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void quantumitems$spendMaterialWindow(Player player, ItemStack result, CallbackInfo ci) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || repairItemCountCost <= 0) {
            return;
        }
        // Reached through the menu's own slot rather than by shadowing
        // inputSlots, which is declared on ItemCombinerMenu and so is not
        // visible to a mixin targeting the subclass. Slot 1 is the material
        // slot; the anvil's slot definitions fix that.
        ItemStack material = ((AnvilMenu) (Object) this).getSlot(1).getItem();
        if (!material.has(ModRegistry.QUANTUM_LINK.get())
                || material.getCount() > repairItemCountCost) {
            return; // vanilla shrinks it, and shrink goes through setCount
        }
        // The whole window is about to be deleted. Spend it properly instead:
        // setCount(0) debits the pool and retires the member, so the network
        // agrees with the world before vanilla clears an empty husk.
        material.setCount(0);
    }
}
