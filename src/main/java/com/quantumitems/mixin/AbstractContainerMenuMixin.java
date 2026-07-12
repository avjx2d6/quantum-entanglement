package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Menu behaviour of a carried window, mirroring vanilla gestures:
 *
 * <ul>
 * <li><b>Left click on a matching plain slot</b>: the slot's items flow into
 * the pool and the window is laid down into the slot (cursor empties) — the
 * exact feel of vanilla "click stack onto stack, they combine and land".
 * If the pool cannot take everything (max stack cap), the leftover stays in
 * the slot and the window stays on the cursor.</li>
 * <li><b>Right click on a matching plain slot</b>: deposits ONE plain item
 * into the slot, keeping the window (and the rest of the pool) on the
 * cursor — vanilla place-one. Depositing the last pooled item ends the
 * network honestly.</li>
 * <li><b>Drag (quick-craft)</b>: distributes PLAIN items extracted from the
 * pool across the touched slots (left drag — an even share, right drag — one
 * each), exactly like vanilla drag, with the window and the remaining pool
 * staying on the cursor. Draining the pool ends the network. A creative
 * middle-clone drag cashes the window out first — cloning linked stacks is
 * banned.</li>
 * <li>Every click on a carried window first reconciles it (anti-dupe: a
 * client round-trip can hand the server a fresh instance; adopting it as
 * canonical keeps count changes flowing into the pool).</li>
 * </ul>
 *
 * <p>Shift-click is handled separately in {@code moveItemStackTo} below.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Shadow
    @Final
    private Set<Slot> quickcraftSlots;

    @Shadow
    private int quickcraftType;

    @Invoker("resetQuickcraft")
    abstract void quantumitems$resetQuickcraft();

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void quantumitems$windowClick(int slotId, int button, ClickType clickType, Player player,
                                          CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        ItemStack carried = self.getCarried();
        if (!carried.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        boolean clientSide = player.level().isClientSide();
        if (engine == null && !clientSide) {
            return; // server thread without a running engine
        }
        if (engine != null) {
            engine.reconcile(carried); // adopt as canonical before any count change
            if (!carried.has(ModRegistry.QUANTUM_LINK.get())) {
                return; // reconcile collapsed/wiped it — it is plain now
            }
        }

        if (clickType == ClickType.QUICK_CRAFT) {
            quantumitems$windowDrag(self, button, engine, ci);
            return;
        }

        // Only a normal left/right pickup onto a matching, non-empty plain slot.
        if (clickType != ClickType.PICKUP || slotId < 0 || slotId >= self.slots.size()) {
            return;
        }
        Slot slot = self.slots.get(slotId);
        ItemStack inSlot = slot.getItem();
        if (inSlot.isEmpty() || inSlot.has(ModRegistry.QUANTUM_LINK.get())
                || !quantumitems$matches(carried, inSlot)) {
            return; // empty / another window / different item: let vanilla handle it
        }

        if (button == 1) {
            // right click: deposit ONE plain into the slot, window stays carried
            if (inSlot.getCount() < Math.min(inSlot.getMaxStackSize(), slot.getMaxStackSize(inSlot))) {
                ItemStack one = quantumitems$extractPlain(self, carried, 1, engine, clientSide);
                if (!one.isEmpty()) {
                    inSlot.grow(one.getCount());
                    slot.setChanged();
                }
            }
        } else {
            // left click: absorb the slot into the pool, then lay the window down
            int absorbed = engine != null
                    ? engine.absorb(carried, inSlot, Integer.MAX_VALUE)
                    : quantumitems$clientAbsorb(carried, inSlot, Integer.MAX_VALUE);
            if (absorbed > 0) {
                slot.setChanged();
            }
            if (slot.getItem().isEmpty() && slot.mayPlace(carried)) {
                slot.setByPlayer(carried);
                self.setCarried(ItemStack.EMPTY);
            }
        }
        ci.cancel();
    }

    /**
     * Drag with a carried window: on release, each touched slot receives its
     * vanilla share as PLAIN items extracted from the pool. The start/continue
     * phases only track slots and pass through untouched.
     */
    private void quantumitems$windowDrag(AbstractContainerMenu self, int button,
                                         QuantumEngine engine, CallbackInfo ci) {
        int header = button & 3;
        if (header != 2) {
            return; // start / add-slot phases: vanilla just tracks
        }
        ItemStack carried = self.getCarried();
        boolean clientSide = engine == null;
        if (quickcraftType == 2) {
            // creative middle-clone drag: cloning linked stacks is banned
            if (engine != null) {
                engine.cashOutToPlain(carried);
            } else {
                carried.remove(ModRegistry.QUANTUM_LINK.get());
            }
            return; // vanilla clones the now-plain stack
        }
        int share = quickcraftType == 1 ? 1
                : quickcraftSlots.isEmpty() ? 0 : carried.getCount() / quickcraftSlots.size();
        if (share > 0) {
            for (Slot slot : quickcraftSlots) {
                ItemStack inSlot = slot.getItem();
                if (!AbstractContainerMenu.canItemQuickReplace(slot, carried, true)
                        || !slot.mayPlace(carried)
                        || inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
                    continue;
                }
                int room = Math.min(carried.getMaxStackSize(), slot.getMaxStackSize(carried))
                        - inSlot.getCount();
                int place = Math.min(Math.min(share, room), carried.getCount());
                if (place <= 0) {
                    continue;
                }
                ItemStack portion = quantumitems$extractPlain(self, carried, place, engine, clientSide);
                if (portion.isEmpty()) {
                    break; // pool exhausted
                }
                if (inSlot.isEmpty()) {
                    slot.setByPlayer(portion);
                } else {
                    inSlot.grow(portion.getCount());
                    slot.setChanged();
                }
                carried = self.getCarried(); // may have gone plain/empty on the last share
                if (carried.isEmpty()) {
                    break;
                }
            }
        }
        quantumitems$resetQuickcraft();
        self.broadcastChanges();
        ci.cancel();
    }

    /**
     * Takes {@code amount} PLAIN items out of the carried window. Taking the
     * final pooled items cashes the window out first (network ends, items
     * conserved), so the extraction is a normal plain split. Returns what was
     * taken; updates the carried stack (may become empty).
     */
    private static ItemStack quantumitems$extractPlain(AbstractContainerMenu self, ItemStack carried,
                                                       int amount, QuantumEngine engine, boolean clientSide) {
        if (amount >= carried.getCount()) {
            if (engine != null) {
                engine.cashOutToPlain(carried);
            } else if (clientSide) {
                carried.remove(ModRegistry.QUANTUM_LINK.get());
            }
        }
        ItemStack taken = carried.split(amount); // window: engine split -> plain; plain: vanilla
        if (self.getCarried().isEmpty()) {
            self.setCarried(ItemStack.EMPTY);
        }
        return taken;
    }

    /**
     * Double-click collect (PICKUP_ALL) consults this per slot. Its sweep does
     * {@code safeTake(...)} and then {@code carried.grow(taken.getCount())},
     * DISCARDING the taken stack object — a whole-take of a window would add
     * its count to the plain cursor while the network and its sibling live on:
     * a pool-sized dupe. Windows are storage anchors; collect never takes from
     * them. (A carried window still absorbs loose plain — those slots are not
     * linked, so this guard does not fire for them.)
     */
    @Inject(method = "canTakeItemForPickAll", at = @At("HEAD"), cancellable = true)
    private void quantumitems$pickAllSkipsWindows(ItemStack carried, Slot slot,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (slot.getItem().has(ModRegistry.QUANTUM_LINK.get())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Shift-click (and every other {@code moveItemStackTo} user) may not
     * merge-drain a window into existing plain stacks — vanilla's merge phase
     * runs before its empty-slot phase, so a matching partial stack in the
     * destination would siphon the pool and dissolve the network. When the
     * destination holds such a partial: relocate the window whole into an
     * empty slot if one exists (link intact), otherwise collapse it to plain
     * and let vanilla merge ordinary items. With no partial around, vanilla's
     * own empty-slot phase already relocates the window whole via split.
     */
    @Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
    private void quantumitems$quickMoveWindow(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!stack.has(ModRegistry.QUANTUM_LINK.get()) || stack.isEmpty()) {
            return;
        }
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null && engine.reconcile(stack) != QuantumEngine.Status.CANONICAL) {
            return; // now plain or wiped — vanilla handles what is left
        }
        boolean partialInRange = false;
        for (int i = startIndex; i < endIndex; i++) {
            Slot slot = self.slots.get(i);
            ItemStack inSlot = slot.getItem();
            if (!inSlot.isEmpty() && !inSlot.has(ModRegistry.QUANTUM_LINK.get())
                    && ItemStack.isSameItemSameComponents(stack, inSlot)
                    && inSlot.getCount() < Math.min(inSlot.getMaxStackSize(), slot.getMaxStackSize(inSlot))) {
                partialInRange = true;
                break;
            }
        }
        if (!partialInRange) {
            return; // vanilla's empty-slot phase relocates the window whole
        }
        int emptyIndex = -1;
        for (int i = reverseDirection ? endIndex - 1 : startIndex;
                reverseDirection ? i >= startIndex : i < endIndex;
                i += reverseDirection ? -1 : 1) {
            Slot slot = self.slots.get(i);
            if (slot.getItem().isEmpty() && slot.mayPlace(stack)) {
                emptyIndex = i;
                break;
            }
        }
        if (emptyIndex >= 0) {
            // relocate whole: copyAndClear routes through the engine on the
            // server (canonical registry follows the moved instance)
            self.slots.get(emptyIndex).setByPlayer(stack.copyAndClear());
            cir.setReturnValue(true);
        } else {
            // only merge targets remain: collapse, vanilla merges plain
            if (engine != null) {
                engine.cashOutToPlain(stack);
            } else {
                stack.remove(ModRegistry.QUANTUM_LINK.get()); // client prediction mirror
            }
        }
    }

    private static boolean quantumitems$matches(ItemStack window, ItemStack plain) {
        return plain.is(window.getItem())
                && window.getComponentsPatch()
                        .forget(type -> type == ModRegistry.QUANTUM_LINK.get())
                        .equals(plain.getComponentsPatch());
    }

    /**
     * Client-side prediction mirror of {@code QuantumEngine.absorb}: the window's
     * synced count IS the pool, so the client computes the same outcome.
     */
    private static int quantumitems$clientAbsorb(ItemStack window, ItemStack plain, int requested) {
        int absorbed = Math.min(Math.min(requested, plain.getCount()),
                window.getMaxStackSize() - window.getCount());
        if (absorbed <= 0) {
            return 0;
        }
        window.grow(absorbed);
        plain.shrink(absorbed);
        return absorbed;
    }
}
