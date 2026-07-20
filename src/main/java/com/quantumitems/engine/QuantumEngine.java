package com.quantumitems.engine;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumDebug;
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
    /**
     * key → the container last seen holding that member. Remote pool changes
     * call {@code setChanged()} on it — that persists the fresh count and
     * fires comparators reading the container (the wireless-redstone core).
     * Refreshed at every reconcile touchpoint; a stale entry costs at most a
     * harmless spurious setChanged.
     */
    private final Map<Long, WeakReference<net.minecraft.world.Container>> holders = new HashMap<>();
    /** Reentrancy guard: our own writes to stack counts must not re-enter pool logic. */
    private int internalWrites;
    /**
     * Depth of player-gesture handling on the server thread (menu clicks,
     * block right-clicks). Whole-take relocation — a window travelling with
     * its link — is a PLAYER privilege; automation (machine ticks, Create
     * arms, funnels) taking a whole window gets it cashed out to plain
     * instead. A machine must never hold a window: its state breaks when a
     * remote collapse empties the stack under it (the frozen-arm bug).
     */
    private int playerGestures;

    public void beginPlayerGesture() {
        playerGestures++;
    }

    public void endPlayerGesture() {
        playerGestures--;
    }

    private QuantumEngine(MinecraftServer server) {
        this.server = server;
    }

    public static void start(MinecraftServer server) {
        instance = new QuantumEngine(server);
        instance.sweepLoneMemberNetworks();
    }

    /**
     * Boot-time cleanup of lone-member anomalies left in saves from before
     * the rule existed. Safe by the same ruling that created the rule: the
     * network dies; if its last window still sleeps in item NBT somewhere,
     * wake-up reconciliation sees a dead networkId and wipes it — which is
     * now the intended fate of an anomalous clone.
     */
    private void sweepLoneMemberNetworks() {
        QuantumNetworks networks = QuantumNetworks.get(server);
        var loneIds = networks.all().entrySet().stream()
                .filter(entry -> entry.getValue().aliveMembers.size() <= 1)
                .map(Map.Entry::getKey)
                .toList();
        for (int networkId : loneIds) {
            QuantumNetworks.Network network = networks.network(networkId);
            if (network != null) {
                debug("boot sweep: net#" + networkId + " has " + network.aliveMembers.size()
                        + " member(s) -> anomaly, network deleted");
                dissolve(networkId, network, networks);
            }
        }
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
            pendingCreativeRetirements.remove(key);
        }
        if (!componentsMatchSnapshot(stack, network)) {
            collapse(stack, link, network, networks);
            return Status.COLLAPSED;
        }
        if (network.aliveMembers.size() == 1) {
            // Lone-member anomaly surfacing (save predating the cleanup): the
            // network is deleted and this last clone wipes with it — the
            // vanished sibling either died with the items or left with them.
            dissolve(link.networkId(), network, networks);
            return Status.DEAD;
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
            debug("setCount IGNORED as transient copy: net#" + link.networkId() + " m" + link.memberId()
                    + " seen=" + seenCount + " new=" + newCount + " pool stays " + network.pool);
            return false;
        }
        if (existing != stack) {
            if (seenCount != network.pool) {
                // A count write against a STALE baseline: a woken sleeper (its
                // canonical died with a broken shulker / chunk unload) or an
                // orphan simulate copy. The intended delta is relative to a
                // count the pool never had — applying it would corrupt or drain
                // the pool (the GC heisenbug). Let vanilla size the instance;
                // a real window heals at its next reconcile touch.
                debug("setCount IGNORED, stale baseline: net#" + link.networkId() + " m" + link.memberId()
                        + " seen=" + seenCount + " pool=" + network.pool + " new=" + newCount);
                return false;
            }
            canonical.put(key, new WeakReference<>(stack)); // adopt: no live competitor, in-sync baseline
            pendingCreativeRetirements.remove(key);
        }
        if (!componentsMatchSnapshot(stack, network)) {
            collapse(stack, link, network, networks);
            return false; // vanilla applies the write to the now-plain stack
        }
        int delta = newCount - seenCount;
        int newPool = Math.max(0, network.pool + delta);
        if (newPool == 0) {
            debug("setCount net#" + link.networkId() + " m" + link.memberId() + ": " + seenCount + "->" + newCount
                    + " => pool 0, dissolving");
            dissolve(link.networkId(), network, networks);
        } else {
            debug("setCount net#" + link.networkId() + " m" + link.memberId() + ": " + seenCount + "->" + newCount
                    + " (delta " + delta + ") => pool " + network.pool + "->" + newPool);
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
        // A live copy of an existing window (simulation probes — Create arms
        // poll with copy+split every tick): the pool is none of its business,
        // and adopting it would steal canonicity from the real window (stale
        // depot displays). Vanilla splits the copy locally.
        QuantumLinkData earlyLink = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (earlyLink != null) {
            WeakReference<ItemStack> ref = canonical.get(key(earlyLink.networkId(), earlyLink.memberId()));
            ItemStack existing = ref != null ? ref.get() : null;
            if (existing != null && existing != stack && !existing.isEmpty()) {
                return ItemStack.EMPTY; // yield NOTHING: a copy must never mint items past the pool
            }
        }
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
            if (playerGestures <= 0) {
                // automation taking the whole window: cash out — plain leaves,
                // the network ends honestly, no machine ever holds a window
                cashOutToPlain(stack);
                return null; // vanilla splits the now-plain stack
            }
            debug("split whole-take net#" + link.networkId() + " m" + link.memberId()
                    + " -> window relocates (pool " + network.pool + ")");
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
        debug("split net#" + link.networkId() + " m" + link.memberId() + " take " + taken
                + " plain => pool " + network.pool + "->" + (network.pool - taken));
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
        QuantumLinkData earlyLink = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (earlyLink != null) {
            WeakReference<ItemStack> ref = canonical.get(key(earlyLink.networkId(), earlyLink.memberId()));
            ItemStack existing = ref != null ? ref.get() : null;
            if (existing != null && existing != stack && !existing.isEmpty()) {
                return ItemStack.EMPTY; // a live copy moves nothing; no canonicity theft, no minting
            }
        }
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
        trackHolder(window, inventory);
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
        debug("absorb net#" + link.networkId() + " m" + link.memberId() + " +" + absorbed
                + " => pool " + network.pool + "->" + (network.pool + absorbed));
        network.pool += absorbed;
        networks.setDirty();
        rawSetCount(window, network.pool);
        pushToMembers(link.networkId(), network, window);
        plain.shrink(absorbed);
        return absorbed;
    }

    /**
     * Pickup absorption that composes with vanilla instead of overriding it.
     * Walks the inventory in vanilla's own fill order (selected, offhand,
     * storage), keeping a running total of what vanilla will merge into
     * partial plain stacks. Only the remainder that vanilla could not merge
     * before reaching a window is absorbed into that window's pool; whatever
     * is still left flows back to the vanilla pickup (partial stacks and
     * free slots).
     *
     * @return total items absorbed into pools
     */
    public int absorbPickup(Inventory inventory, ItemStack plain) {
        int storage = inventory.items.size();
        int[] order = new int[storage + 2];
        order[0] = inventory.selected;
        order[1] = Inventory.SLOT_OFFHAND;
        for (int i = 0; i < storage; i++) {
            order[i + 2] = i;
        }
        boolean[] visited = new boolean[inventory.getContainerSize()];
        int unmergeable = plain.getCount();
        int totalAbsorbed = 0;
        for (int idx : order) {
            if (unmergeable <= 0) {
                break;
            }
            if (idx < 0 || idx >= visited.length || visited[idx]) {
                continue; // the selected slot appears twice in the walk order
            }
            visited[idx] = true;
            ItemStack candidate = inventory.getItem(idx);
            if (candidate.isEmpty()) {
                continue;
            }
            QuantumLinkData link = candidate.get(ModRegistry.QUANTUM_LINK.get());
            if (link == null) {
                if (ItemStack.isSameItemSameComponents(candidate, plain)) {
                    unmergeable -= candidate.getMaxStackSize() - candidate.getCount();
                }
                continue;
            }
            QuantumNetworks.Network network = QuantumNetworks.get(server).network(link.networkId());
            if (network == null || !plain.is(network.item)
                    || !plain.getComponentsPatch().equals(network.snapshot)) {
                continue;
            }
            int absorbed = absorb(candidate, plain, unmergeable);
            totalAbsorbed += absorbed;
            unmergeable -= absorbed;
        }
        return totalAbsorbed;
    }

    /**
     * The creative screen is client-authoritative: a window arriving in a
     * creative slot packet is a direct edit of the pool. The client already
     * applies quantum split semantics locally (partial splits go plain), so
     * an incoming count below the pool means "items were extracted", above —
     * creative conjuring; both are legitimate in creative.
     */
    public void creativeUpdate(ItemStack stack) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null || stack.isEmpty()) {
            return;
        }
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network == null || !network.aliveMembers.contains(link.memberId())) {
            wipe(stack);
            return;
        }
        canonical.put(key(link.networkId(), link.memberId()), new WeakReference<>(stack));
        pendingCreativeRetirements.remove(key(link.networkId(), link.memberId()));
        if (!componentsMatchSnapshot(stack, network)) {
            collapse(stack, link, network, networks);
            return;
        }
        int newPool = Math.max(1, Math.min(stack.getCount(), stack.getMaxStackSize()));
        if (newPool != network.pool) {
            network.pool = newPool;
            networks.setDirty();
        }
        rawSetCount(stack, network.pool);
        pushToMembers(link.networkId(), network, stack);
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
        network.aliveMembers.remove(Integer.valueOf(link.memberId()));
        if (network.aliveMembers.isEmpty()) {
            debug("windowDestroyed net#" + link.networkId() + " m" + link.memberId()
                    + ": last member gone => network removed (pool was " + network.pool + ")");
            networks.removeNetwork(link.networkId());
        } else {
            debug("windowDestroyed net#" + link.networkId() + " m" + link.memberId()
                    + " retired, members left " + network.aliveMembers);
            networks.setDirty();
            collapseIfLoneMember(link.networkId(), network, networks);
        }
    }

    /**
     * A single-member network is an anomaly (creation yields two windows and
     * survival never retires members — only creative edits do). A member that
     * vanished anomalously either ceased to exist with its items or left the
     * network taking them along — in both readings the remaining clone must
     * NOT keep serving the pool, so the whole network is deleted and the
     * survivor wipes to zero (author's ruling, playtest 2026-07-16). A live
     * survivor dies right here; an untracked one (asleep in item NBT) dies at
     * first touch instead — see the lone-member check in {@link #reconcile} —
     * because dissolving with a registered member is exactly what wake-up
     * reconciliation already treats as "dead network -> wipe".
     */
    private void collapseIfLoneMember(int networkId, QuantumNetworks.Network network, QuantumNetworks networks) {
        if (network.aliveMembers.size() != 1) {
            return;
        }
        int survivorId = network.aliveMembers.first();
        debug("net#" + networkId + " down to lone m" + survivorId + " -> anomaly, network deleted");
        dissolve(networkId, network, networks);
    }

    /** Transfers window identity from the current instance to a fresh copy. */
    private ItemStack moveWindow(ItemStack stack, QuantumLinkData link) {
        ItemStack moved = stack.copy();
        wipe(stack);
        canonical.put(key(link.networkId(), link.memberId()), new WeakReference<>(moved));
        pendingCreativeRetirements.remove(key(link.networkId(), link.memberId()));
        return moved;
    }

    /**
     * Rule 5: a creative slot packet REPLACED a window with something that no
     * longer carries the same link (a merged plain stack, another item, air).
     * Creative is client-authoritative and items are free there — the honest
     * reading is "this window ceased to exist": the member retires (pool and
     * siblings live on; the last member takes the network with it). Without
     * this the member lingered forever as a ghost network.
     */
    public void creativeSlotReplaced(ItemStack oldStack, ItemStack newStack) {
        QuantumLinkData oldLink = oldStack.get(ModRegistry.QUANTUM_LINK.get());
        if (oldLink == null) {
            return;
        }
        QuantumLinkData newLink = newStack.get(ModRegistry.QUANTUM_LINK.get());
        if (oldLink.equals(newLink)) {
            return; // same member came back — creativeUpdate handles the count
        }
        debug("creative replaced window net#" + oldLink.networkId() + " m" + oldLink.memberId()
                + " with " + (newStack.isEmpty() ? "air" : newStack.getItem()) + " -> retirement deferred");
        // Deferred, not immediate: a creative MOVE is two slot packets (old
        // slot cleared, new slot set) and the second one re-adopts the member,
        // cancelling this. Only a member nobody re-adopts within the grace
        // window was genuinely deleted.
        pendingCreativeRetirements.put(key(oldLink.networkId(), oldLink.memberId()), CREATIVE_RETIREMENT_GRACE_TICKS);
    }

    private static final int CREATIVE_RETIREMENT_GRACE_TICKS = 3;
    private final Map<Long, Integer> pendingCreativeRetirements = new HashMap<>();

    /** Ticked from ServerEvents: retire creative-deleted members whose grace expired un-readopted. */
    public void flushCreativeRetirements() {
        if (pendingCreativeRetirements.isEmpty()) {
            return;
        }
        var iterator = pendingCreativeRetirements.entrySet().iterator();
        QuantumNetworks networks = QuantumNetworks.get(server);
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft > 0) {
                entry.setValue(ticksLeft);
                continue;
            }
            iterator.remove();
            long key = entry.getKey();
            int networkId = (int) (key >>> 32);
            int memberId = (int) key;
            QuantumNetworks.Network network = networks.network(networkId);
            if (network == null || !network.aliveMembers.contains(memberId)) {
                continue;
            }
            debug("creative retirement net#" + networkId + " m" + memberId + " (grace expired)");
            canonical.remove(key);
            network.aliveMembers.remove(Integer.valueOf(memberId));
            if (network.aliveMembers.isEmpty()) {
                networks.removeNetwork(networkId);
            } else {
                networks.setDirty();
                collapseIfLoneMember(networkId, network, networks);
            }
        }
    }

    /**
     * Rule 1 cash-out: a window that is about to exist as a free item collapses
     * to plain here, taking the whole pool with it (siblings wiped, network
     * ended). A husk of a dead network/member is simply emptied. Item count is
     * always conserved and no linked item is ever left in the world.
     */
    public void cashOutToPlain(ItemStack stack) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return;
        }
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(link.networkId());
        if (network == null || !network.aliveMembers.contains(link.memberId())) {
            debug("cashOut net#" + link.networkId() + " m" + link.memberId() + ": dead husk -> emptied");
            wipe(stack); // dead network or already-cashed-out member: nothing left
            return;
        }
        debug("cashOut net#" + link.networkId() + " m" + link.memberId() + " -> plain x" + network.pool);
        collapse(stack, link, network, networks);
    }

    /** Dissolves a network: every live window is emptied, the entry removed. */
    public void dissolve(int networkId, QuantumNetworks.Network network, QuantumNetworks networks) {
        debug("dissolve net#" + networkId + " (pool 0): emptying members " + network.aliveMembers);
        for (int member : network.aliveMembers) {
            long key = key(networkId, member);
            WeakReference<ItemStack> ref = canonical.remove(key);
            ItemStack memberStack = ref != null ? ref.get() : null;
            if (memberStack != null) {
                wipe(memberStack);
            }
            wipeFromHolder(key, networkId, member);
            notifyHolder(key);
            holders.remove(key);
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
        debug("collapse net#" + link.networkId() + " => plain x" + pool + " at m" + link.memberId()
                + ", siblings " + network.aliveMembers + " wiped");
        for (int member : network.aliveMembers) {
            long key = key(link.networkId(), member);
            WeakReference<ItemStack> ref = canonical.remove(key);
            if (member != link.memberId()) {
                ItemStack memberStack = ref != null ? ref.get() : null;
                if (memberStack != null) {
                    wipe(memberStack);
                }
                wipeFromHolder(key, link.networkId(), member);
            }
            notifyHolder(key);
            holders.remove(key);
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

    /**
     * Heals every window in a container: stale counts reconcile to the pool,
     * dead husks are cleared, holders are registered, and the container is
     * marked changed if anything moved — so comparators and persistence are
     * correct immediately, not at the first touch. Runs deferred on chunk
     * load / container-entity join (never during deserialization itself).
     */
    public void reconcileContainer(net.minecraft.world.Container container) {
        Set<Long> seen = new java.util.HashSet<>();
        boolean changed = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.has(ModRegistry.QUANTUM_LINK.get())) {
                continue;
            }
            int before = stack.getCount();
            QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
            reconcileScan(stack, seen);
            if (stack.isEmpty()) {
                debug("loadHeal net#" + link.networkId() + " m" + link.memberId()
                        + ": dead husk cleared (was " + before + ")");
                container.setItem(i, ItemStack.EMPTY);
                changed = true;
            } else {
                trackHolder(stack, container);
                if (stack.getCount() != before) {
                    debug("loadHeal net#" + link.networkId() + " m" + link.memberId()
                            + ": count " + before + "->" + stack.getCount());
                    changed = true;
                }
            }
        }
        if (changed) {
            container.setChanged();
        }
    }

    /** Remembers (weakly) which container currently holds a member's window. */
    public void trackHolder(ItemStack stack, @Nullable net.minecraft.world.Container holder) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null || holder == null) {
            return;
        }
        holders.put(key(link.networkId(), link.memberId()), new WeakReference<>(holder));
    }

    /**
     * Kills a member's window INSIDE its tracked holder by LINK, not by
     * instance identity. Canonical refs legitimately drift to copies
     * (last-touch-wins), and a wipe through a stale ref leaves the real
     * stack sitting in the container as a live-looking husk — the
     * phantom-pedestal bug: the network is gone but the resonator still
     * holds a linked stack the player can pick up for a second.
     */
    private void wipeFromHolder(long key, int networkId, int memberId) {
        WeakReference<net.minecraft.world.Container> ref = holders.get(key);
        net.minecraft.world.Container holder = ref != null ? ref.get() : null;
        if (holder == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < holder.getContainerSize(); i++) {
            ItemStack slotStack = holder.getItem(i);
            QuantumLinkData link = slotStack.get(ModRegistry.QUANTUM_LINK.get());
            if (link != null && link.networkId() == networkId && link.memberId() == memberId) {
                debug("holder-scan wipe net#" + networkId + " m" + memberId + " (stale canonical)");
                wipe(slotStack);
                changed = true;
            }
        }
        if (changed) {
            holder.setChanged();
        }
    }

    /** setChanged() on a member's last known holder (persistence + comparators). */
    private void notifyHolder(long key) {
        WeakReference<net.minecraft.world.Container> ref = holders.get(key);
        net.minecraft.world.Container holder = ref != null ? ref.get() : null;
        if (holder == null) {
            if (ref != null) {
                holders.remove(key);
            }
            return;
        }
        holder.setChanged();
    }

    /** Registers a freshly entangled window as canonical. */
    public void adopt(ItemStack stack) {
        QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
        if (link != null) {
            canonical.put(key(link.networkId(), link.memberId()), new WeakReference<>(stack));
            pendingCreativeRetirements.remove(key(link.networkId(), link.memberId()));
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
            notifyHolder(key);
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

    /** Echoes a pool-mutation trace to chat when {@code /quantum debug} is on. */
    private void debug(String message) {
        QuantumDebug.log(server, message);
    }

    /** Live snapshot for {@code /quantum networks}: is a member's window instance still around? */
    public boolean hasLiveInstance(int networkId, int memberId) {
        WeakReference<ItemStack> ref = canonical.get(key(networkId, memberId));
        ItemStack stack = ref != null ? ref.get() : null;
        return stack != null && !stack.isEmpty();
    }

    private static long key(int networkId, int memberId) {
        return ((long) networkId << 32) | (memberId & 0xFFFFFFFFL);
    }
}
