package com.quantumitems.engine;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime core of quantum entanglement. Lives for the duration of one server
 * run; rebuilt from scratch on every start (the registry of canonical stack
 * instances is memory-only, the pool itself persists in {@link QuantumNetworks}).
 *
 * <p>All entry points are server-thread only: {@link #onServerThread()} returns
 * null from any other thread, which makes the ItemStack mixins fall through to
 * vanilla behaviour on clients and worker threads.
 */
public final class QuantumEngine {
    private static volatile QuantumEngine instance;

    private final MinecraftServer server;
    /** key = networkId << 32 | memberId → canonical live instance of that window. */
    private final Map<Long, WeakReference<ItemStack>> canonical = new HashMap<>();
    /** Reentrancy guard: our own writes to stack counts must not re-enter pool logic. */
    private int internalWrites;

    private QuantumEngine(MinecraftServer server) {
        this.server = server;
    }

    public static void start(MinecraftServer server) {
        instance = new QuantumEngine(server);
    }

    public static void stop() {
        instance = null;
    }

    @Nullable
    public static QuantumEngine onServerThread() {
        QuantumEngine engine = instance;
        return engine != null && engine.server.isSameThread() ? engine : null;
    }

    public boolean isInternalWrite() {
        return internalWrites > 0;
    }

    public enum Status {
        /** No link data on the stack. */
        PLAIN,
        /** Stack is the live window of its member slot; count fixed to pool. */
        CANONICAL,
        /** Network no longer exists (or member retired) — stack was wiped. */
        DEAD,
        /** Another live instance owns this member id — stack was wiped. */
        DUPLICATE,
        /** Components diverged from the snapshot — network collapsed into this stack (now plain). */
        COLLAPSED
    }

    /**
     * Validates a linked stack against the authority and fixes it up:
     * adopts it as the canonical instance, corrects a stale count, wipes
     * dead/duplicate stacks, collapses diverged ones. Safe to call at any
     * touchpoint; idempotent.
     */
    public Status reconcile(ItemStack stack) {
        return reconcile(stack, true);
    }

    /**
     * @param wipeDuplicates whether a stack that lost the canonicity race is
     *                       zeroed out. Materialization points (slots, ground,
     *                       split) pass true; count writes pass false because
     *                       vanilla constantly sizes transient copies
     *                       (copyWithCount, simulated extractions) that must
     *                       stay untouched.
     */
    public Status reconcile(ItemStack stack, boolean wipeDuplicates) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return Status.PLAIN;
        }
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network == null || !network.aliveMembers.contains(link.memberId())) {
            wipe(stack);
            return Status.DEAD;
        }
        long key = key(link.networkId(), link.memberId());
        WeakReference<ItemStack> ref = canonical.get(key);
        ItemStack existing = ref != null ? ref.get() : null;
        if (existing != null && existing != stack && !existing.isEmpty()) {
            if (wipeDuplicates) {
                wipe(stack);
            }
            return Status.DUPLICATE;
        }
        if (existing != stack) {
            canonical.put(key, new WeakReference<>(stack));
        }
        if (!componentsMatchSnapshot(stack, network)) {
            collapse(stack, link, network, networks);
            return Status.COLLAPSED;
        }
        if (stack.getCount() != network.pool) {
            rawSetCount(stack, network.pool);
        }
        return Status.CANONICAL;
    }

    /**
     * Pool-aware replacement for {@code ItemStack.setCount} on linked stacks.
     * The delta the caller intended (relative to the count it saw) is applied
     * to the pool; every live window is then pushed the new pool value.
     *
     * @return true if the vanilla write must be cancelled
     */
    public boolean handleSetCount(ItemStack stack, int newCount) {
        int seenCount = stack.getCount();
        Status status = reconcile(stack, false);
        switch (status) {
            case PLAIN, COLLAPSED -> {
                return false; // vanilla applies the write to a plain stack
            }
            case DUPLICATE -> {
                // a transient copy being sized (copyWithCount, simulations):
                // none of our business, vanilla writes to the copy
                return false;
            }
            case DEAD -> {
                return true; // stack is wiped; swallow the write
            }
            default -> {
            }
        }
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        int delta = newCount - seenCount;
        int newPool = Math.max(0, network.pool + delta);
        if (newPool == 0) {
            dissolve(link.networkId(), network, networks);
        } else {
            network.pool = newPool;
            networks.setDirty();
            rawSetCount(stack, newPool);
            pushToMembers(link.networkId(), network, stack);
        }
        return true;
    }

    /**
     * Pool-aware replacement for {@code ItemStack.split} on linked stacks.
     * Separating a part extracts plain items from the pool; taking the whole
     * remainder is a window move — the returned stack keeps the link and
     * becomes the canonical instance.
     *
     * @return the stack to return from split, or null to let vanilla proceed
     */
    @Nullable
    public ItemStack handleSplit(ItemStack stack, int amount) {
        Status status = reconcile(stack);
        switch (status) {
            case PLAIN, COLLAPSED -> {
                return null; // vanilla splits the now-plain stack
            }
            case DEAD, DUPLICATE -> {
                return ItemStack.EMPTY;
            }
            default -> {
            }
        }
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        int taken = Math.min(amount, network.pool);
        if (taken <= 0) {
            return ItemStack.EMPTY;
        }
        if (taken >= network.pool) {
            return moveWindow(stack, link);
        }
        // copyWithCount calls setCount on a copy that still carries the link —
        // guard it so the registry does not flag the nascent portion as a dupe
        ItemStack portion;
        internalWrites++;
        try {
            portion = stack.copyWithCount(taken);
            portion.remove(ModRegistry.QUANTUM_LINK.get());
        } finally {
            internalWrites--;
        }
        network.pool -= taken;
        networks.setDirty();
        rawSetCount(stack, network.pool);
        pushToMembers(link.networkId(), network, stack);
        return portion;
    }

    /**
     * Pool-aware replacement for {@code ItemStack.copyAndClear} on linked
     * stacks: it is a whole-stack move, exactly like split of the full pool.
     *
     * @return the stack to return, or null to let vanilla proceed
     */
    @Nullable
    public ItemStack handleCopyAndClear(ItemStack stack) {
        Status status = reconcile(stack);
        return switch (status) {
            case PLAIN, COLLAPSED -> null;
            case DEAD, DUPLICATE -> ItemStack.EMPTY;
            default -> moveWindow(stack, stack.get(ModRegistry.QUANTUM_LINK.get()));
        };
    }

    /** Transfers window identity from the current instance to a fresh copy. */
    private ItemStack moveWindow(ItemStack stack, QuantumLinkData link) {
        ItemStack moved = stack.copy();
        wipe(stack);
        canonical.put(key(link.networkId(), link.memberId()), new WeakReference<>(moved));
        return moved;
    }

    /** Dissolves a network: every live window is emptied, the entry removed. */
    public void dissolve(int networkId, QuantumNetworks.Network network, QuantumNetworks networks) {
        for (int member : network.aliveMembers) {
            WeakReference<ItemStack> ref = canonical.remove(key(networkId, member));
            ItemStack memberStack = ref != null ? ref.get() : null;
            if (memberStack != null) {
                wipe(memberStack);
            }
        }
        networks.removeNetwork(networkId);
    }

    /**
     * Property change detected: the modified stack leaves as plain with the
     * whole pool; the network dissolves, all other windows vanish.
     */
    private void collapse(ItemStack stack, QuantumLinkData link,
                          QuantumNetworks.Network network, QuantumNetworks networks) {
        int pool = network.pool;
        for (int member : network.aliveMembers) {
            if (member == link.memberId()) {
                canonical.remove(key(link.networkId(), member));
                continue;
            }
            WeakReference<ItemStack> ref = canonical.remove(key(link.networkId(), member));
            ItemStack memberStack = ref != null ? ref.get() : null;
            if (memberStack != null) {
                wipe(memberStack);
            }
        }
        networks.removeNetwork(link.networkId());
        internalWrites++;
        try {
            stack.remove(ModRegistry.QUANTUM_LINK.get());
            stack.setCount(pool);
        } finally {
            internalWrites--;
        }
    }

    /** Registers a freshly entangled window as canonical. */
    public void adopt(ItemStack stack) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link != null) {
            canonical.put(key(link.networkId(), link.memberId()), new WeakReference<>(stack));
        }
    }

    /** Drops a member's canonical registration without touching the pool. */
    public void deregister(int networkId, int memberId) {
        canonical.remove(key(networkId, memberId));
    }

    private void pushToMembers(int networkId, QuantumNetworks.Network network, ItemStack source) {
        for (int member : network.aliveMembers) {
            WeakReference<ItemStack> ref = canonical.get(key(networkId, member));
            ItemStack memberStack = ref != null ? ref.get() : null;
            if (memberStack != null && memberStack != source && !memberStack.isEmpty()) {
                rawSetCount(memberStack, network.pool);
            }
        }
    }

    private boolean componentsMatchSnapshot(ItemStack stack, QuantumNetworks.Network network) {
        if (!stack.is(network.item)) {
            return false;
        }
        DataComponentPatch current = stack.getComponentsPatch()
                .forget(type -> type == ModRegistry.QUANTUM_LINK.get());
        return current.equals(network.snapshot);
    }

    private void wipe(ItemStack stack) {
        internalWrites++;
        try {
            stack.remove(ModRegistry.QUANTUM_LINK.get());
            stack.setCount(0);
        } finally {
            internalWrites--;
        }
    }

    private void rawSetCount(ItemStack stack, int count) {
        internalWrites++;
        try {
            stack.setCount(count);
        } finally {
            internalWrites--;
        }
    }

    private static long key(int networkId, int memberId) {
        return ((long) networkId << 32) | (memberId & 0xFFFFFFFFL);
    }
}
