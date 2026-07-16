package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A resonator is a pedestal-slot of the ritual circle: exactly one stack,
 * placed and taken by hand only. It deliberately implements WorldlyContainer
 * with zero accessible faces and registers no item capability — hoppers,
 * droppers and pipes cannot see inside. "An artifact, not logistics" is a
 * physical property of the block, not a rule players must remember.
 *
 * It remains a Container so the quantum engine can track it as a holder:
 * windows resting here receive pool pushes and setChanged like any chest.
 */
public class ResonatorBlockEntity extends BlockEntity implements WorldlyContainer {
    private static final int[] NO_SLOTS = new int[0];

    private ItemStack item = ItemStack.EMPTY;

    public ResonatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.RESONATOR_BE.get(), pos, state);
    }

    public ItemStack displayedItem() {
        return item;
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
        setChanged();
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
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null && !stack.isEmpty()) {
            engine.reconcile(stack);
            engine.trackHolder(stack, this);
        }
        setChanged();
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
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // --- persistence + client sync (the displayed stack renders in-world) ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!item.isEmpty()) {
            tag.put("item", item.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        item = tag.contains("item") ? ItemStack.parseOptional(registries, tag.getCompound("item")) : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
