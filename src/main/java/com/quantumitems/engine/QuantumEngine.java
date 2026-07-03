package com.quantumitems.engine;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** Windows currently lying on the ground, for forced entity-data syncs. */
    private final Map<Long, WeakReference<ItemEntity>> groundEntities = new HashMap<>();
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
        /** Second sighting of the same member within one scan pass — stack was wiped. */
        DUPLICATE,
        /** Components diverged from the snapshot — network collapsed into this stack (now plain). */
        COLLAPSED
    }

    /**
     * Validates a linked stack against the authority and fixes it up:
     * adopts it as the canonical instance (last touch wins — vanilla
     * legitimately replaces slot instances with equal copies on packet
     * round-trips and chunk reloads, so identity must never be punished),
     * corrects a stale count, wipes dead stacks, collapses diverged ones.
     * Safe to call at any touchpoint; idempotent. Item duplication is
     * impossible regardless of how many instances float around: every
     * extraction is bounded by the pool.
     */
    public Status reconcile(ItemStack stack) {
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
        if (ref == null || ref.get() != stack) {
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
     * Scan-pass variant: inventories and open containers are swept slot by
     * slot with a shared per-pass set, so a member sighted twice in one pass
     * (creative clone, /give copy) loses its second physical stack. This is
     * the only place duplicates are wiped — display cleanup, not dupe
     * protection; the pool already bounds extraction.
     */
    public Status reconcileScan(ItemStack stack, Set<Long> seenThisPass) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return Status.PLAIN;
        }
        long key = key(link.networkId(), link.memberId());
        if (!seenThisPass.add(key)) {
            wipe(stack);
            return Status.DUPLICATE;
        }
        return reconcile(stack);
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
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return false;
        }
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network == null || !network.aliveMembers.contains(link.memberId())) {
            wipe(stack);
            return true; // dead network: stack wiped, write swallowed
        }
        long key = key(link.networkId(), link.memberId());
        WeakReference<ItemStack> ref = canonical.get(key);
        ItemStack existing = ref != null ? ref.get() : null;
        if (existing != null && existing != stack && !existing.isEmpty()) {
            // a transient copy being sized while the live window exists
            // (copyWithCount, simulated extractions): vanilla writes to the
            // copy, the pool is none of its business
            return false;
        }
        if (existing != stack) {
            canonical.put(key, new WeakReference<>(stack)); // adopt: no live competitor
        }
        if (!componentsMatchSnapshot(stack, network)) {
            collapse(stack, link, network, networks);
            return false; // vanilla applies the write to the now-plain stack
        }
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
            case DEAD -> {
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
            case DEAD -> ItemStack.EMPTY;
            default -> moveWindow(stack, stack.get(ModRegistry.QUANTUM_LINK.get()));
        };
    }

    /**
     * Replacement for {@code Inventory.add} on linked stacks. Vanilla adds
     * items by growing a zero-count copy in the slot and shrinking the
     * source ({@code addResource}) — that pattern would tear a window apart.
     * Instead the window transfers wholesale into a free slot; the source
     * instance is emptied to honour the caller contract.
     *
     * @return true/false to report to the caller, or null to let vanilla add
     *         a plain stack normally
     */
    @Nullable
    public Boolean handleInventoryAdd(net.minecraft.world.entity.player.Inventory inventory, int slot, ItemStack stack) {
        Status status = reconcile(stack);
        switch (status) {
            case PLAIN, COLLAPSED -> {
                return null;
            }
            case DEAD -> {
                return true; // wiped: nothing left to add
            }
            default -> {
            }
        }
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        int target = slot >= 0 && inventory.getItem(slot).isEmpty() ? slot : inventory.getFreeSlot();
        if (target < 0) {
            return false; // caller drops the stack whole; the link travels with it
        }
        ItemStack window = moveWindow(stack, link);
        inventory.setItem(target, window);
        return true;
    }

    /**
     * Absorbs plain items into a window's pool, up to the item's max stack
     * size. The plain stack must match the network snapshot exactly.
     *
     * @return how many items were absorbed (0 = nothing happened)
     */
    public int absorb(ItemStack window, ItemStack plain, int requested) {
        if (reconcile(window) != Status.CANONICAL) {
            return 0;
        }
        QuantumLinkData link = window.get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (plain.has(ModRegistry.QUANTUM_LINK.get()) || !plain.is(network.item)
                || !plain.getComponentsPatch().equals(network.snapshot)) {
            return 0;
        }
        int absorbed = Math.min(Math.min(requested, plain.getCount()), window.getMaxStackSize() - network.pool);
        if (absorbed <= 0) {
            return 0;
        }
        network.pool += absorbed;
        networks.setDirty();
        rawSetCount(window, network.pool);
        pushToMembers(link.networkId(), network, window);
        plain.shrink(absorbed);
        return absorbed;
    }

    /**
     * Decides where a picked-up plain stack should go, walking the slots in
     * vanilla's own order (selected, offhand, then storage). The first
     * matching stack wins: a plain stack with room means vanilla handles the
     * pickup untouched; a window met first absorbs into the pool.
     */
    @Nullable
    public ItemStack findPickupAbsorber(Inventory inventory, ItemStack plain) {
        int storage = inventory.items.size();
        int[] order = new int[storage + 2];
        order[0] = inventory.selected;
        order[1] = Inventory.SLOT_OFFHAND;
        for (int i = 0; i < storage; i++) {
            order[i + 2] = i;
        }
        for (int idx : order) {
            ItemStack candidate = inventory.getItem(idx);
            if (candidate.isEmpty()) {
                continue;
            }
            QuantumLinkData link = candidate.get(ModRegistry.QUANTUM_LINK.get());
            if (link == null) {
                if (ItemStack.isSameItemSameComponents(candidate, plain)
                        && candidate.getCount() < candidate.getMaxStackSize()) {
                    return null; // vanilla merges into this stack first — stay out of the way
                }
                continue;
            }
            QuantumNetworks.Network network = QuantumNetworks.get(server).network(link.networkId());
            if (network != null && plain.is(network.item)
                    && plain.getComponentsPatch().equals(network.snapshot)
                    && network.pool < candidate.getMaxStackSize()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Right-click deposit: carrying a window over a matching plain stack puts
     * one plain item from the pool into the slot — mirroring vanilla's
     * "right click deposits one". Depositing the very last item is refused
     * (vanilla swap applies instead).
     */
    public boolean depositOne(ItemStack window, ItemStack slotPlain) {
        if (reconcile(window) != Status.CANONICAL) {
            return false;
        }
        QuantumLinkData link = window.get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network.pool < 2
                || slotPlain.has(ModRegistry.QUANTUM_LINK.get())
                || !slotPlain.is(network.item)
                || !slotPlain.getComponentsPatch().equals(network.snapshot)
                || slotPlain.getCount() >= slotPlain.getMaxStackSize()) {
            return false;
        }
        network.pool--;
        networks.setDirty();
        rawSetCount(window, network.pool);
        pushToMembers(link.networkId(), network, window);
        slotPlain.grow(1);
        return true;
    }

    /**
     * Ground windows: an ItemEntity's stack only reaches clients through
     * SynchedEntityData, so count pushes into a ground window must be
     * followed by a forced entity-data sync.
     */
    public void registerGroundEntity(QuantumLinkData link, ItemEntity entity) {
        groundEntities.put(key(link.networkId(), link.memberId()), new WeakReference<>(entity));
    }

    /** Absorbs plain item entities lying next to a ground window. */
    public void absorbNearbyPlains(ItemEntity windowEntity) {
        ItemStack window = windowEntity.getItem();
        if (!window.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        for (ItemEntity other : windowEntity.level().getEntitiesOfClass(ItemEntity.class,
                windowEntity.getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            if (other == windowEntity || other.isRemoved()) {
                continue;
            }
            ItemStack plain = other.getItem();
            if (plain.isEmpty() || plain.has(ModRegistry.QUANTUM_LINK.get())) {
                continue;
            }
            int absorbed = absorb(window, plain, Integer.MAX_VALUE);
            if (absorbed > 0) {
                if (plain.isEmpty()) {
                    other.setItem(ItemStack.EMPTY);
                    other.discard();
                } else {
                    other.setItem(plain.copy()); // forces entity-data sync of the leftover
                }
            }
        }
    }

    /** Periodic pass over registered ground windows: vacuum neighbours, drop dead refs. */
    public void sweepGroundWindows() {
        groundEntities.values().removeIf(ref -> {
            ItemEntity entity = ref.get();
            return entity == null || entity.isRemoved();
        });
        for (WeakReference<ItemEntity> ref : List.copyOf(groundEntities.values())) {
            ItemEntity entity = ref.get();
            if (entity != null && !entity.isRemoved()) {
                absorbNearbyPlains(entity);
            }
        }
    }

    /**
     * Crafting-consumption guard: taking a craft result consumes ingredients
     * through {@code removeItem(slot, 1)} → split. With pool == 1 that split
     * is a whole-take, and the returned window would be silently discarded by
     * the crafting code — duping the last item. Pre-collapsing turns the
     * ingredient into a plain item the consumption can eat honestly.
     */
    public void precollapseIfSingleton(ItemStack stack) {
        if (reconcile(stack) != Status.CANONICAL) {
            return;
        }
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network.pool == 1) {
            collapse(stack, link, network, networks);
        }
    }

    /**
     * A window's item entity was destroyed (despawn, lava, cactus, void).
     * The member retires; the pool is untouched — a window burned, not the
     * items. If it was the last window, the pool dies with it.
     */
    public void windowDestroyed(ItemStack stack) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return;
        }
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network == null || !network.aliveMembers.contains(link.memberId())) {
            return;
        }
        long key = key(link.networkId(), link.memberId());
        WeakReference<ItemStack> ref = canonical.get(key);
        ItemStack existing = ref != null ? ref.get() : null;
        if (existing != null && existing != stack && !existing.isEmpty()) {
            return; // a stale copy burned; the real window lives elsewhere
        }
        canonical.remove(key);
        groundEntities.remove(key);
        network.aliveMembers.remove(Integer.valueOf(link.memberId()));
        if (network.aliveMembers.isEmpty()) {
            networks.removeNetwork(link.networkId());
        } else {
            networks.setDirty();
        }
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
            long key = key(networkId, member);
            WeakReference<ItemStack> ref = canonical.remove(key);
            ItemStack memberStack = ref != null ? ref.get() : null;
            if (memberStack != null) {
                wipe(memberStack);
                syncGround(key, memberStack);
            }
            groundEntities.remove(key);
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
            long key = key(link.networkId(), member);
            WeakReference<ItemStack> ref = canonical.remove(key);
            if (member != link.memberId()) {
                ItemStack memberStack = ref != null ? ref.get() : null;
                if (memberStack != null) {
                    wipe(memberStack);
                    syncGround(key, memberStack);
                }
            }
            groundEntities.remove(key);
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
            long key = key(networkId, member);
            WeakReference<ItemStack> ref = canonical.get(key);
            ItemStack memberStack = ref != null ? ref.get() : null;
            if (memberStack == null) {
                continue;
            }
            if (memberStack != source && !memberStack.isEmpty()) {
                rawSetCount(memberStack, network.pool);
            }
            syncGround(key, memberStack);
        }
    }

    /** Forces the entity-data sync when a member's stack lives in an ItemEntity. */
    private void syncGround(long key, ItemStack expected) {
        WeakReference<ItemEntity> ref = groundEntities.get(key);
        if (ref == null) {
            return;
        }
        ItemEntity entity = ref.get();
        if (entity == null || entity.isRemoved()) {
            groundEntities.remove(key);
            return;
        }
        if (entity.getItem() == expected) {
            ((GroundWindowSync) entity).quantumitems$forceItemSync();
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
