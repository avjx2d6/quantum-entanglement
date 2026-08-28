package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import com.quantumitems.engine.WindowSlotOps;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The inventory base class of most modded machines and transports (Create's
 * inventories extend it). Its {@code extractItem} works by COPIES: a partial
 * take REPLACES the slot with {@code copyWithCount} (orphaning the canonical
 * window instance — later writes to the slot get ignored as transient and the
 * merged items are eaten by the next heal) and returns a linked raw fragment;
 * a whole take hands out the window itself to code that treats stacks as
 * count carriers. Rule 2 generalized: extraction only ever yields PLAIN — a
 * partial take routes through {@code split} (pool debited, the canonical
 * instance stays in the slot), a whole take cashes out first (full
 * extraction, network ends honestly), simulate returns plain probes.
 */
@Mixin(value = ItemStackHandler.class, remap = false)
public abstract class ItemStackHandlerMixin {

    @Invoker("onContentsChanged")
    abstract void quantumitems$onContentsChanged(int slot);

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$extractFromWindow(int slot, int amount, boolean simulate,
                                                CallbackInfoReturnable<ItemStack> cir) {
        if (amount <= 0) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        ItemStackHandler self = (ItemStackHandler) (Object) this;
        if (slot < 0 || slot >= self.getSlots()) {
            return;
        }
        ItemStack existing = self.getStackInSlot(slot);
        if (!existing.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        if (engine.reconcile(existing) != QuantumEngine.Status.CANONICAL) {
            return; // now plain or wiped — vanilla handles what is left
        }
        int taking = Math.min(existing.getCount(), Math.min(amount, existing.getMaxStackSize()));
        if (simulate) {
            cir.setReturnValue(WindowSlotOps.plainProbe(existing, taking));
            return;
        }
        if (taking >= existing.getCount()) {
            engine.cashOutToPlain(existing); // full extraction: network ends honestly
            return; // vanilla whole-take branch hands out the now-plain stack
        }
        // partial: split debits the pool and keeps THE INSTANCE in the slot
        ItemStack portion = existing.split(taking);
        quantumitems$onContentsChanged(slot);
        cir.setReturnValue(portion);
    }

    /**
     * At most one window of a network may sit inside machine inventories at a
     * time — see {@link QuantumEngine#machineSlotTaken}. The second is simply
     * not accepted: the stack comes back untouched, nothing is destroyed and
     * nothing collapses, exactly as when a machine's slot is full.
     *
     * <p>This is what makes a doubled autocrafting arrangement unbuildable
     * without knowing anything about the mod that built it. A depot, a basin or
     * a single crafter holding ONE window is untouched, because one window
     * cannot be counted twice.
     */
    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void quantumitems$insertWindow(int slot, ItemStack stack, boolean simulate,
                                           CallbackInfoReturnable<ItemStack> cir) {
        if (stack.isEmpty() || !stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        if (engine.machineSlotTaken(stack)) {
            cir.setReturnValue(stack); // refused whole, simulate and real alike
            return;
        }
        if (!simulate) {
            engine.claimMachineSlot(stack);
        }
    }
}
