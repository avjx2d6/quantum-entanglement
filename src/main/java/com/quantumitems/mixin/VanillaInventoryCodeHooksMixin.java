package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
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
 * NeoForge routes vanilla hopper transfers through IItemHandler wrappers, and
 * both hooks mishandle windows:
 *
 * <ul>
 * <li>{@code insertHook} removes an item (pool −1 through our split), and on a
 * failed insert restores the slot from a STALE COPY while discarding the
 * extracted item — draining the pool every retry tick.</li>
 * <li>{@code extractHook} simulates the extraction with a LINKED copy, which
 * never merges with plain stacks in the hopper — items spread out one per
 * slot and the hopper stalls, even though the real extraction yields plain
 * items that would merge fine.</li>
 * </ul>
 *
 * Both are reimplemented simulate-first with an honest probe: what the real
 * extraction will actually yield (plain for a partial pool, the window itself
 * for the last item).
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

    @Inject(method = "insertHook", at = @At("HEAD"), cancellable = true)
    private static void quantumitems$insertHook(HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || !quantumitems$hasLinked(hopper)) {
            return;
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

    @Inject(method = "extractHook", at = @At("HEAD"), cancellable = true)
    private static void quantumitems$extractHook(Level level, Hopper dest, CallbackInfoReturnable<Boolean> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        Optional<Pair<IItemHandler, Object>> source = quantumitems$sourceHandler(level, dest);
        if (source.isEmpty()) {
            return; // no handler: the original hook returns null and vanilla continues
        }
        IItemHandler handler = source.get().getKey();
        boolean anyLinked = false;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i).has(ModRegistry.QUANTUM_LINK.get())) {
                anyLinked = true;
                break;
            }
        }
        if (!anyLinked) {
            return;
        }
        cir.setReturnValue(quantumitems$pull(engine, dest, handler));
    }

    private static boolean quantumitems$hasLinked(HopperBlockEntity hopper) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            if (hopper.getItem(i).has(ModRegistry.QUANTUM_LINK.get())) {
                return true;
            }
        }
        return false;
    }

    /** What the real one-item extraction will yield: plain, or the window itself when pool == 1. */
    private static ItemStack quantumitems$probe(ItemStack window) {
        if (window.getCount() > 1) {
            ItemStack probe = window.copyWithCount(1);
            probe.remove(ModRegistry.QUANTUM_LINK.get());
            return probe;
        }
        return window.copy();
    }

    private static boolean quantumitems$push(QuantumEngine engine, HopperBlockEntity hopper, IItemHandler handler) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack inSlot = hopper.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            ItemStack probe;
            if (inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
                if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
                    continue; // wiped or collapsed in place
                }
                probe = quantumitems$probe(inSlot);
            } else {
                probe = inSlot.copyWithCount(1);
            }
            if (!ItemHandlerHelper.insertItemStacked(handler, probe, true).isEmpty()) {
                continue; // does not fit — nothing was touched
            }
            ItemStack extracted = hopper.removeItem(i, 1);
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, extracted, false);
            if (!remainder.isEmpty()) {
                // simulate/real mismatch (exotic handlers): undo honestly
                ItemStack nowInSlot = hopper.getItem(i);
                if (remainder.has(ModRegistry.QUANTUM_LINK.get())) {
                    hopper.setItem(i, remainder); // bounce the moved window home
                } else if (nowInSlot.has(ModRegistry.QUANTUM_LINK.get())) {
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

    private static Boolean quantumitems$pull(QuantumEngine engine, Hopper dest, IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack inSlot = handler.getStackInSlot(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            ItemStack probe;
            if (inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
                if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
                    continue;
                }
                probe = quantumitems$probe(inSlot);
            } else {
                probe = handler.extractItem(i, 1, true);
                if (probe.isEmpty()) {
                    continue;
                }
            }
            for (int j = 0; j < dest.getContainerSize(); j++) {
                ItemStack destStack = dest.getItem(j);
                boolean fits = dest.canPlaceItem(j, probe)
                        && (destStack.isEmpty()
                                || destStack.getCount() < destStack.getMaxStackSize()
                                        && destStack.getCount() < dest.getMaxStackSize()
                                        && ItemStack.isSameItemSameComponents(probe, destStack));
                if (!fits) {
                    continue;
                }
                ItemStack extracted = handler.extractItem(i, 1, false);
                if (extracted.isEmpty()) {
                    break;
                }
                if (destStack.isEmpty()) {
                    dest.setItem(j, extracted);
                } else {
                    destStack.grow(extracted.getCount());
                    dest.setItem(j, destStack);
                }
                dest.setChanged();
                return true;
            }
        }
        return false;
    }
}
