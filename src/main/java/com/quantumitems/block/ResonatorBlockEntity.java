package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A resonator is a pedestal-slot of the ritual circle, with Create-depot
 * presentation: a stack laid down by a player SLIDES from their side onto
 * the center (Create's TransportedItemStack math — beltPosition eases from
 * .25 to .5 by a quarter of the remainder per tick, nudging the lay angle
 * as it goes), ritual outputs materialize centered.
 *
 * Automation-proof by construction: WorldlyContainer with zero accessible
 * faces and no item capability — hoppers, droppers and pipes cannot see
 * inside. Still a Container, so the quantum engine tracks it as a holder.
 */
public class ResonatorBlockEntity extends SyncedBlockEntity implements WorldlyContainer {
    private static final int[] NO_SLOTS = new int[0];

    private ItemStack item = ItemStack.EMPTY;
    @Nullable
    private Direction insertedFrom;
    private float beltPosition = 0.5f;
    private float prevBeltPosition = 0.5f;
    private int angle;

    public ResonatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.RESONATOR_BE.get(), pos, state);
        angle = Math.floorMod(pos.hashCode() * 31, 360);
    }

    public ItemStack displayedItem() {
        return item;
    }

    @Nullable
    public Direction insertedFrom() {
        return insertedFrom;
    }

    public float slideOffset(float partialTick) {
        return net.minecraft.util.Mth.lerp(partialTick, prevBeltPosition, beltPosition);
    }

    public int layAngle() {
        return angle;
    }

    /** Both sides tick the slide; the server owns the truth, the client animates it. */
    public void tick() {
        prevBeltPosition = beltPosition;
        float diff = 0.5f - beltPosition;
        if (diff > 1 / 512f) {
            if (diff > 1 / 32f) {
                angle += 1;
            }
            beltPosition += diff / 4f;
        }
        selfHeal();
        syncOccupiedFlag();
    }

    /**
     * Mirrors "a stack lies here" into the OCCUPIED blockstate every server
     * tick. Engine wipes mutate the held stack in place (no setItem call), so
     * this is the one spot that reliably notices emptiness however it happened;
     * the state flip rides the never-lost blockstate sync AND re-broadcasts the
     * BE data, while the renderer gates on the flag — both together close the
     * client-phantom desync for good.
     */
    private void syncOccupiedFlag() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        boolean occupied = !item.isEmpty();
        if (state.hasProperty(ResonatorBlock.OCCUPIED) && state.getValue(ResonatorBlock.OCCUPIED) != occupied) {
            level.setBlock(worldPosition, state.setValue(ResonatorBlock.OCCUPIED, occupied), 3);
        }
    }

    /**
     * Server-side backstop against phantom windows. When a network dies, the
     * collapse sweep clears its sibling windows through the engine's in-memory
     * holder map — but that map is empty right after a world load (before the
     * deferred chunk-load reconcile runs) and can drift stale, so a sibling can
     * be left sitting on its pedestal as a dead husk that only heals when a
     * player finally touches it. Reconciling our own window on a slow, staggered
     * cadence closes that gap: a husk empties within a tick or two on its own,
     * with no dependence on holder tracking, events, or load timing. A live
     * window reconciles to a no-op.
     */
    private void selfHeal() {
        if (level == null || level.isClientSide || !item.has(ModRegistry.QUANTUM_LINK.get())) {
            return;
        }
        long stagger = Math.floorMod(worldPosition.getX() * 7L + worldPosition.getZ() * 13L, 16L);
        if ((level.getGameTime() & 15L) != stagger) {
            return;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        engine.reconcile(item);
        if (item.isEmpty()) {
            item = ItemStack.EMPTY;
            insertedFrom = null;
            setChanged();
        }
    }

    /** Player-facing insertion: the stack arrives from the player's side and slides in. */
    public void layDown(ItemStack stack, Direction from) {
        insertedFrom = from;
        prevBeltPosition = 0.25f;
        beltPosition = 0.25f;
        setItem(0, stack);
    }

    /** The core locks its circle while a ritual runs; resonators ask before letting hands in. */
    public boolean isLockedByRitual() {
        if (level == null) {
            return false;
        }
        for (BlockPos corner : new BlockPos[]{
                worldPosition.offset(2, 0, 2), worldPosition.offset(2, 0, -2),
                worldPosition.offset(-2, 0, 2), worldPosition.offset(-2, 0, -2)}) {
            if (level.getBlockEntity(corner) instanceof QuantumCoreBlockEntity core
                    && core.isRitualRunning() && core.isCircleMember(worldPosition)) {
                return true;
            }
        }
        return false;
    }

    // --- WorldlyContainer: sealed to automation ---

    @Override
    public int[] getSlotsForFace(Direction side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
    }

    // --- Container ---

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return item.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? item : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = item.split(amount);
        notifyUpdate();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = item;
        item = ItemStack.EMPTY;
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot != 0) {
            return;
        }
        item = stack;
        if (stack.isEmpty()) {
            insertedFrom = null;
            prevBeltPosition = 0.5f;
            beltPosition = 0.5f;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null && !stack.isEmpty()) {
            engine.reconcile(stack);
            engine.trackHolder(stack, this);
        }
        notifyUpdate();
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        item = ItemStack.EMPTY;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        // The engine pushes pool changes via setChanged (holder contract);
        // the displayed count must follow to the client immediately.
        sendData();
    }

    // --- persistence + client sync ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!item.isEmpty()) {
            tag.put("item", item.save(registries));
        }
        if (insertedFrom != null) {
            tag.putByte("insertedFrom", (byte) insertedFrom.get3DDataValue());
        }
        tag.putFloat("beltPosition", beltPosition);
        tag.putInt("angle", angle);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        item = tag.contains("item") ? ItemStack.parseOptional(registries, tag.getCompound("item")) : ItemStack.EMPTY;
        insertedFrom = tag.contains("insertedFrom") ? Direction.from3DDataValue(tag.getByte("insertedFrom")) : null;
        beltPosition = tag.contains("beltPosition") ? tag.getFloat("beltPosition") : 0.5f;
        prevBeltPosition = beltPosition;
        if (tag.contains("angle")) {
            angle = tag.getInt("angle");
        }
    }
}
