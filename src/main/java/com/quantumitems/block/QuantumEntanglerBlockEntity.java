package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import com.quantumitems.menu.QuantumEntanglerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Uses a vanilla Container (not an ItemStackHandler) on purpose: vanilla
 * container semantics move stacks through {@code ItemStack.split} and whole
 * instances, which the quantum engine understands. ItemStackHandler's
 * copy-based partial extraction would silently replace canonical window
 * instances with copies.
 */
public class QuantumEntanglerBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_SHARD = 1;
    public static final int SLOT_OUT_A = 2;
    public static final int SLOT_OUT_B = 3;
    public static final int PROCESS_TIME = 60;

    private final NonNullList<ItemStack> items = NonNullList.withSize(4, ItemStack.EMPTY);
    private int progress;

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return index == 0 ? progress : PROCESS_TIME;
        }

        @Override
        public void set(int index, int value) {
            if (index == 0) {
                progress = value;
            }
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    public QuantumEntanglerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.QUANTUM_ENTANGLER_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, QuantumEntanglerBlockEntity blockEntity) {
        if (blockEntity.canProcess((ServerLevel) level)) {
            blockEntity.progress++;
            if (blockEntity.progress >= PROCESS_TIME) {
                blockEntity.progress = 0;
                blockEntity.entangle((ServerLevel) level, pos);
            }
            blockEntity.setChanged();
        } else if (blockEntity.progress != 0) {
            blockEntity.progress = 0;
            blockEntity.setChanged();
        }
    }

    private boolean canProcess(ServerLevel level) {
        ItemStack input = items.get(SLOT_INPUT);
        ItemStack shard = items.get(SLOT_SHARD);
        if (input.isEmpty() || !shard.is(ModRegistry.QUANTUM_SHARD.get())) {
            return false;
        }
        if (!items.get(SLOT_OUT_A).isEmpty() || !items.get(SLOT_OUT_B).isEmpty()) {
            return false;
        }
        if (input.getMaxStackSize() <= 1) {
            return false;
        }
        QuantumLinkData link = input.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return true;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null || engine.reconcile(input) != QuantumEngine.Status.CANONICAL) {
            return false; // dead/duplicate/diverged input was fixed up in place
        }
        QuantumNetworks.Network network = QuantumNetworks.get(level.getServer()).network(link.networkId());
        return network != null && network.aliveMembers.size() < QuantumNetworks.MAX_MEMBERS;
    }

    private void entangle(ServerLevel level, BlockPos pos) {
        ItemStack input = items.get(SLOT_INPUT);
        QuantumNetworks networks = QuantumNetworks.get(level.getServer());
        QuantumEngine engine = QuantumEngine.onServerThread();
        QuantumLinkData link = input.get(ModRegistry.QUANTUM_LINK.get());

        ItemStack outA;
        ItemStack outB;
        if (link == null) {
            // Fresh entanglement: one plain stack becomes two windows of a new network.
            int networkId = networks.createNetwork(input);
            outA = input.copy();
            outA.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(networkId, 1));
            outB = input.copy();
            outB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(networkId, 2));
            com.quantumitems.QuantumDebug.log(level.getServer(), "created net#" + networkId + " "
                    + input.getItem() + " x" + input.getCount() + " members[1, 2]");
        } else {
            // Expansion: the window passes through AS THE SAME INSTANCE (a copy
            // would be flagged as a duplicate by the canonical registry), and a
            // new window of the same network appears beside it.
            int member = networks.addMember(link.networkId());
            if (member < 0) {
                return;
            }
            outA = input;
            outB = input.copy();
            outB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(link.networkId(), member));
            com.quantumitems.QuantumDebug.log(level.getServer(),
                    "expand net#" + link.networkId() + " +member " + member);
        }
        if (engine != null) {
            engine.adopt(outA);
            engine.adopt(outB);
        }

        items.set(SLOT_INPUT, ItemStack.EMPTY);
        items.get(SLOT_SHARD).shrink(1);
        items.set(SLOT_OUT_A, outA);
        items.set(SLOT_OUT_B, outB);
        setChanged();
        level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.8f, 1.4f);
    }

    // --- Container ---

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            setChanged();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    // --- persistence / menu ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        progress = tag.getInt("progress");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block." + QuantumItemsMod.MOD_ID + ".quantum_entangler");
    }

    @Override
    @Nullable
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new QuantumEntanglerMenu(containerId, playerInventory, this, dataAccess);
    }
}
