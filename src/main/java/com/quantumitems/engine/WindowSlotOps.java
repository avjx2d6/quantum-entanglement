package com.quantumitems.engine;

import com.quantumitems.ModRegistry;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * The window-aware half of a capability wrapper's insert/extract, shared by
 * the {@code InvWrapper} and {@code SidedInvWrapper} mixins.
 *
 * <p>Both wrappers need the same behaviour and used to carry their own copy of
 * it; the copies drifted (the unsided one quietly stopped honouring the
 * container's own placement rules), so the rules live here once. Only what
 * genuinely differs — the sided face checks and the slot limit — stays at the
 * call site.
 *
 * <p>Insertion: vanilla merges by copying the <em>incoming</em> stack, growing
 * the copy by the slot's count and replacing the slot with it — that would
 * overwrite a window with a plain stack, orphaning the pool and bypassing the
 * {@code setCount} hook. The plain items flow into the pool instead.
 *
 * <p>Extraction is Rule 2 for every capability consumer (Create chutes and
 * funnels, pipes): extraction only ever yields PLAIN. A simulate returns a
 * plain probe (a linked probe leaks into mod logic and its raw setCount spawns
 * orphan fragments); a whole take cashes the window out first, so a full
 * extraction ends the network honestly instead of relocating the window into
 * transport limbo. Partial takes already flow through split.
 */
public final class WindowSlotOps {

    private WindowSlotOps() {
    }

    /**
     * @return the stack the wrapper must return, or null to let vanilla run.
     *         The caller has already cleared any face restriction.
     */
    @Nullable
    public static ItemStack extract(Container inv, int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return null;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return null;
        }
        ItemStack inSlot = inv.getItem(slot);
        if (!inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
            return null;
        }
        if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
            return null; // now plain or wiped — vanilla handles what is left
        }
        engine.trackHolder(inSlot, inv);
        int taking = Math.min(inSlot.getCount(), amount);
        if (simulate) {
            return plainProbe(inSlot, taking);
        }
        if (taking >= inSlot.getCount()) {
            engine.cashOutToPlain(inSlot); // full extraction: plain out, network ends
        }
        return null; // partial (or now-plain whole): vanilla removeItem -> split yields plain
    }

    /**
     * @param slotLimit the wrapper's own {@code getSlotLimit(slot)} — the
     *                  container may cap a slot below the item's stack size,
     *                  and vanilla insertion honours that cap.
     * @return the remainder the wrapper must return, or null to let vanilla run.
     *         The caller has already cleared any face restriction.
     */
    @Nullable
    public static ItemStack insert(Container inv, int slot, ItemStack stack, boolean simulate, int slotLimit) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || stack.isEmpty() || stack.has(ModRegistry.QUANTUM_LINK.get())) {
            return null; // only plain items flowing into a window slot are special
        }
        ItemStack inSlot = inv.getItem(slot);
        if (!inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
            return null; // ordinary slot: let vanilla insertion run
        }
        if (!inv.canPlaceItem(slot, stack)) {
            return stack; // the container refuses this item here, exactly as vanilla would
        }
        if (engine.reconcile(inSlot) != QuantumEngine.Status.CANONICAL) {
            return null; // wiped/collapsed in place — it is plain now, vanilla handles it
        }
        int capacity = Math.min(stack.getMaxStackSize(), slotLimit);
        int room = Math.min(stack.getCount(), capacity - inSlot.getCount());
        if (room <= 0) {
            return stack; // pool is full: nothing inserted
        }
        if (simulate) {
            return stack.getCount() > room ? stack.copyWithCount(stack.getCount() - room) : ItemStack.EMPTY;
        }
        engine.trackHolder(inSlot, inv); // before absorb: the push must notify this holder too
        ItemStack remainder = stack.copy();
        if (engine.absorb(inSlot, remainder, room) > 0) {
            inv.setChanged(); // vanilla insertion would have marked the target changed
        }
        return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
    }

    /**
     * The plain form of a window, at a given count: what every consumer outside
     * the pool is allowed to see. Kept in one place because it defines what
     * counts as link metadata.
     */
    public static ItemStack plainProbe(ItemStack window, int count) {
        ItemStack probe = window.copyWithCount(count);
        probe.remove(ModRegistry.QUANTUM_LINK.get());
        return probe;
    }
}
