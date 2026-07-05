package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.VanillaInventoryCodeHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Merging is generic now — the {@code isSameItemSameComponents} hook lets
 * every transport combine windows and plain stacks, so pulling from a window
 * and pushing plain into one both work through plain vanilla/NeoForge code.
 *
 * <p>One NeoForge pattern still misbehaves and is not about "understanding
 * windows": {@code insertHook} removes an item first ({@code removeItem} →
 * our split drains the pool) and, when the target turns out to be full,
 * "restores" the slot from a stale pre-removal copy while discarding the
 * extracted item. For plain stacks that round-trips to a no-op; for a window
 * the pool was already decremented and the extracted item is destroyed —
 * a silent drain every retry tick. The fix is a simulate-first guard: only
 * remove from a window slot once we know the target will actually take it.
 *
 * <p>{@code extractHook} has the mirror problem only at the last item: it
 * calls {@code destStack.grow(1)} and discards the stack it extracted. For a
 * plain item that is fine, but the last pooled item leaves as a whole window
 * (a relocation, so the pool is not decremented) — grown into the target and
 * then thrown away, the pool lingers at 1 while the item is already gone: a
 * dupe. Pre-collapsing a singleton source window to plain before vanilla
 * extracts turns it into an honest plain take that drains the pool to zero.
 * Player pickups run through {@code Slot}, not this hook, so they still keep
 * the link on a single-item window.
 */
@Mixin(value = VanillaInventoryCodeHooks.class, remap = false)
public abstract class VanillaInventoryCodeHooksMixin {

    @Invoker("getAttachedItemHandler")
    static Optional<Pair<IItemHandler, Object>> quantumitems$attachedHandler(Level level, net.minecraft.core.BlockPos pos, Direction direction) {
        throw new AssertionError();
    }

    @Invoker("getSourceItemHandler")
    static Optional<Pair<IItemHandler, Object>> quantumitems$sourceHandler(Level level, Hopper hopper) {
        throw new AssertionError();
    }

    @Inject(method = "extractHook", at = @At("HEAD"))
    private static void quantumitems$extractHook(Level level, Hopper dest, CallbackInfoReturnable<Boolean> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        Optional<Pair<IItemHandler, Object>> source = quantumitems$sourceHandler(level, dest);
        if (source.isEmpty()) {
            return;
        }
        IItemHandler handler = source.get().getKey();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            // pool > 1 is left untouched — vanilla + the stacking hook pull plain
            // items that merge; only the last item would dupe. And only collapse
            // when the hopper can actually take it, so a full hopper merely
            // pointed at a singleton window never ends the network (or its
            // siblings) without a real transfer.
            if (stack.has(ModRegistry.QUANTUM_LINK.get()) && stack.getCount() == 1
                    && quantumitems$destHasRoom(dest, stack)) {
                engine.precollapseIfSingleton(stack);
            }
        }
        // no cancel: vanilla extractHook now pulls honest plain / multi-item pools
    }

    /** Would the hopper accept one plain copy of this window's item right now? */
    private static boolean quantumitems$destHasRoom(Hopper dest, ItemStack window) {
        ItemStack plain = window.copyWithCount(1);
        plain.remove(ModRegistry.QUANTUM_LINK.get());
        for (int j = 0; j < dest.getContainerSize(); j++) {
            ItemStack destStack = dest.getItem(j);
            if (!dest.canPlaceItem(j, plain)) {
                continue;
            }
            if (destStack.isEmpty()
                    || (destStack.getCount() < Math.min(destStack.getMaxStackSize(), dest.getMaxStackSize())
                            && ItemStack.isSameItemSameComponents(destStack, plain))) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "insertHook", at = @At("HEAD"), cancellable = true)
    private static void quantumitems$insertHook(HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || !quantumitems$hasLinked(hopper)) {
            return; // no window in the hopper: vanilla handles plain pushes fine
        }
        Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);
        Optional<Pair<IItemHandler, Object>> attached =
                quantumitems$attachedHandler(hopper.getLevel(), hopper.getBlockPos(), facing);
        if (attached.isEmpty()) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(quantumitems$push(engine, hopper, attached.get().getKey()));
    }

    private static boolean quantumitems$hasLinked(HopperBlockEntity hopper) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            if (hopper.getItem(i).has(ModRegistry.QUANTUM_LINK.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dropper pushing into an adjacent container: the NeoForge hook runs
     * {@code stack.copy().split(1)} — the copy bypasses the engine, steals
     * canonicity, and at pool==1 the whole-take ships the WINDOW into the
     * target. Reimplement for linked sources with the same rules as the
     * hopper push: only plain ever leaves, the last item collapses in place
     * first (gated on the target having room).
     */
    @Inject(method = "dropperInsertHook", at = @At("HEAD"), cancellable = true)
    private static void quantumitems$dropperInsertHook(Level level, BlockPos pos, DispenserBlockEntity dropper,
                                                       int slot, ItemStack stack,
                                                       CallbackInfoReturnable<Boolean> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        ItemStack inSlot = dropper.getItem(slot);
        if (engine == null || !inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
            cir.setReturnValue(false); // wiped/collapsed in place; retry next activation
            return;
        }
        Direction facing = level.getBlockState(pos).getValue(DropperBlock.FACING);
        Optional<Pair<IItemHandler, Object>> attached = quantumitems$attachedHandler(level, pos, facing);
        if (attached.isEmpty()) {
            cir.setReturnValue(true); // no container: vanilla air-dispense (split -> plain, covered)
            return;
        }
        IItemHandler handler = attached.get().getKey();
        if (!ItemHandlerHelper.insertItemStacked(handler, quantumitems$plainProbe(inSlot), true).isEmpty()) {
            cir.setReturnValue(false); // target has no room — never touch the pool
            return;
        }
        if (inSlot.getCount() == 1) {
            engine.precollapseIfSingleton(inSlot); // last item leaves plain, network ends
        }
        ItemStack extracted = dropper.removeItem(slot, 1); // window pool>1 -> split -> plain
        if (extracted.isEmpty()) {
            cir.setReturnValue(false);
            return;
        }
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, extracted, false);
        if (!remainder.isEmpty()) {
            // simulate/real mismatch (exotic handlers): put the plain back honestly
            ItemStack now = dropper.getItem(slot);
            if (now.has(ModRegistry.QUANTUM_LINK.get())) {
                engine.absorb(now, remainder, remainder.getCount());
            } else if (now.isEmpty()) {
                dropper.setItem(slot, remainder);
            } else {
                now.grow(remainder.getCount());
            }
        }
        cir.setReturnValue(false);
    }

    /** One plain item of a window's kind — automation only ever moves plain. */
    private static ItemStack quantumitems$plainProbe(ItemStack window) {
        ItemStack probe = window.copyWithCount(1);
        probe.remove(ModRegistry.QUANTUM_LINK.get());
        return probe;
    }

    private static boolean quantumitems$push(QuantumEngine engine, HopperBlockEntity hopper, IItemHandler handler) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack inSlot = hopper.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
                if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
                    continue; // wiped or collapsed in place
                }
                // Automation moves only plain: the last pooled item collapses the
                // window to plain first (so it never travels as a window), but
                // only if the target can actually take it.
                if (inSlot.getCount() == 1
                        && ItemHandlerHelper.insertItemStacked(handler, quantumitems$plainProbe(inSlot), true).isEmpty()) {
                    engine.precollapseIfSingleton(inSlot); // inSlot is now plain
                }
            }
            ItemStack probe = inSlot.has(ModRegistry.QUANTUM_LINK.get())
                    ? quantumitems$plainProbe(inSlot)
                    : inSlot.copyWithCount(1);
            if (!ItemHandlerHelper.insertItemStacked(handler, probe, true).isEmpty()) {
                continue; // target has no room — never touch the pool
            }
            ItemStack extracted = hopper.removeItem(i, 1); // window pool>1 -> split -> plain; else plain
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, extracted, false);
            if (!remainder.isEmpty()) {
                // simulate/real mismatch (exotic handlers): put the plain back honestly
                ItemStack nowInSlot = hopper.getItem(i);
                if (nowInSlot.has(ModRegistry.QUANTUM_LINK.get())) {
                    engine.absorb(nowInSlot, remainder, remainder.getCount());
                } else if (nowInSlot.isEmpty()) {
                    hopper.setItem(i, remainder);
                } else {
                    nowInSlot.grow(remainder.getCount());
                }
            }
            return true;
        }
        return false;
    }
}
