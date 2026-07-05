package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two things happen at the head of a container click on a carried window:
 *
 * <ol>
 * <li><b>Anti-dupe.</b> The carried instance is not always the one the engine
 * has registered as canonical (a client round-trip can hand the server a fresh
 * window instance). Reconciling it first adopts it as canonical, so any count
 * change the click makes is applied to the pool instead of being mistaken for a
 * throwaway simulation copy.</li>
 * <li><b>A window is always a sink.</b> Vanilla already lets a carried plain
 * stack flow into a window slot (it grows the slot window → the pool). The
 * reverse — a carried window clicked onto a matching plain stack — would deposit
 * the window's items into the slot and, if the whole pool fits, drain it to zero
 * and dissolve the network. Instead the plain is absorbed into the pool, exactly
 * mirroring the other direction: left click takes the whole stack, right click
 * takes one, the window keeps its link, and the network never dies from a click.
 * A client mirror keeps prediction flicker-free.</li>
 * </ol>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

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

        // Rule 3: a drag distributes COPIES of the carried stack via raw count
        // writes and setByPlayer — with a window that plants live linked clones
        // of one member across slots. Collapse to plain the moment a drag
        // involves a carried window; vanilla then distributes ordinary items.
        if (clickType == ClickType.QUICK_CRAFT) {
            if (engine != null) {
                engine.cashOutToPlain(carried);
            } else {
                carried.remove(ModRegistry.QUANTUM_LINK.get()); // client prediction mirror
            }
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
        int requested = button == 0 ? Integer.MAX_VALUE : 1;
        int absorbed = engine != null
                ? engine.absorb(carried, inSlot, requested)
                : quantumitems$clientAbsorb(carried, inSlot, requested);
        if (absorbed > 0) {
            slot.setChanged();
        }
        ci.cancel(); // a matching plain slot never gets a vanilla deposit of the window
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
