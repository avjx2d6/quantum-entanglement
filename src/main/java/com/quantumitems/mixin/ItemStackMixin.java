package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The choke points of the quantum engine. Every vanilla count mutation
 * funnels through {@code setCount} (shrink/grow/consume are thin wrappers),
 * every partial extraction through {@code split}, and {@code copyAndClear}
 * is a whole-stack move. Most hooks are no-ops unless the stack carries
 * QuantumLinkData and we are on the server thread.
 *
 * <p>{@code isSameItemSameComponents} is the one exception: it is the single
 * root point every transport (vanilla hoppers, shift-click, cursor merges,
 * modded chutes and pipes) consults to decide whether two stacks combine.
 * Making a window and a plain stack of the same item read as "the same"
 * there lets all of them merge generically — the count then flows through
 * the {@code setCount} hook into the shared pool, no per-transport code.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /**
     * A window and a plain stack of the same base item (components equal once
     * the link is ignored) stack together everywhere vanilla asks this. Two
     * windows are left to vanilla: same member stacks, different members or
     * networks stay distinct so separate pools never merge by accident.
     */
    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void quantumitems$linkAgnosticStacking(ItemStack a, ItemStack b,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (a.isEmpty() || b.isEmpty()) {
            return;
        }
        boolean aLinked = a.has(ModRegistry.QUANTUM_LINK.get());
        boolean bLinked = b.has(ModRegistry.QUANTUM_LINK.get());
        if (aLinked && bLinked) {
            // Two live instances of the SAME member (creative clone, /give
            // copy) read component-equal to vanilla — merging a stack with
            // its own alias inflates the count past the pool. Never equal.
            if ((Object) a != (Object) b
                    && java.util.Objects.equals(a.get(ModRegistry.QUANTUM_LINK.get()),
                            b.get(ModRegistry.QUANTUM_LINK.get()))) {
                cir.setReturnValue(false);
            }
            return; // different members/networks: vanilla decides (components differ)
        }
        if (aLinked == bLinked || !ItemStack.isSameItem(a, b)) {
            return; // both plain / different items: vanilla decides
        }
        DataComponentPatch pa = a.getComponentsPatch().forget(t -> t == ModRegistry.QUANTUM_LINK.get());
        DataComponentPatch pb = b.getComponentsPatch().forget(t -> t == ModRegistry.QUANTUM_LINK.get());
        if (pa.equals(pb)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * {@code matches} is STATE IDENTITY — change detection (menu
     * broadcastChanges compares live slots against remembered copies, hand
     * re-equip animation, equipment sync). Two rules for windows:
     * a window and a copy of the same member at the same count are the SAME
     * state (otherwise the alias merge guard below leaks in here, the server
     * resends the slot every tick and the held item twitches forever); and a
     * link↔plain transition at the same count IS a change (otherwise a
     * collapse that keeps the count never reaches the client).
     */
    @Inject(method = "matches(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void quantumitems$windowStateIdentity(ItemStack a, ItemStack b,
                                                         CallbackInfoReturnable<Boolean> cir) {
        boolean aLinked = !a.isEmpty() && a.has(ModRegistry.QUANTUM_LINK.get());
        boolean bLinked = !b.isEmpty() && b.has(ModRegistry.QUANTUM_LINK.get());
        if (aLinked != bLinked) {
            cir.setReturnValue(false); // link appeared/vanished: always a real change
        } else if (aLinked) {
            cir.setReturnValue(a.getCount() == b.getCount()
                    && ItemStack.isSameItem(a, b)
                    && java.util.Objects.equals(a.get(ModRegistry.QUANTUM_LINK.get()),
                            b.get(ModRegistry.QUANTUM_LINK.get())));
        }
    }

    /**
     * Every component write funnels through {@code set} — durability
     * ({@code setDamageValue}), programmatic enchants, renames. A property
     * change on a window collapses the network IMMEDIATELY (the snapshot
     * divergence check would catch it anyway, but only lazily at the next
     * touch, and a diverged window is a desync window). The collapse runs
     * before the write, so the new component lands on an honest plain stack.
     */
    @Inject(method = "set", at = @At("HEAD"))
    private <T> void quantumitems$componentMutation(net.minecraft.core.component.DataComponentType<? super T> type,
                                                    T value,
                                                    CallbackInfoReturnable<T> cir) {
        if (type == ModRegistry.QUANTUM_LINK.get()) {
            return; // deliberate link management (entangler, engine)
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (self.has(ModRegistry.QUANTUM_LINK.get())) {
            engine.cashOutToPlain(self);
        }
    }

    @Inject(method = "setCount", at = @At("HEAD"), cancellable = true)
    private void quantumitems$setCount(int newCount, CallbackInfo ci) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (!self.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        if (engine.handleSetCount(self, newCount)) {
            ci.cancel();
        }
    }

    @Inject(method = "split", at = @At("HEAD"), cancellable = true)
    private void quantumitems$split(int amount, CallbackInfoReturnable<ItemStack> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (!self.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        ItemStack result = engine.handleSplit(self, amount);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    /**
     * Client-side quantum semantics. Server splits are handled (and
     * cancelled) at HEAD, so this only runs where no engine exists — client
     * prediction and the client-authoritative creative screen. It mirrors the
     * law locally: a partial split yields plain items, taking the whole
     * remainder keeps the link. Keeps predictions matching the server and
     * makes creative-screen manipulation produce honest results.
     */
    @Inject(method = "split", at = @At("RETURN"))
    private void quantumitems$stripPartialSplit(int amount, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack) (Object) this;
        ItemStack result = cir.getReturnValue();
        if (result != null && !self.isEmpty() && result.has(ModRegistry.QUANTUM_LINK.get())) {
            result.remove(ModRegistry.QUANTUM_LINK.get());
        }
    }

    @Inject(method = "copyAndClear", at = @At("HEAD"), cancellable = true)
    private void quantumitems$copyAndClear(CallbackInfoReturnable<ItemStack> cir) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.isInternalWrite()) {
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        if (!self.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        ItemStack result = engine.handleCopyAndClear(self);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
