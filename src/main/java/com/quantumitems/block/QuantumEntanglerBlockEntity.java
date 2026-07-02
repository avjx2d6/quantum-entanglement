package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.menu.QuantumEntanglerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;

public class QuantumEntanglerBlockEntity extends BlockEntity implements MenuProvider {
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_SHARD = 1;
    public static final int SLOT_OUT_A = 2;
    public static final int SLOT_OUT_B = 3;
    public static final int PROCESS_TIME = 60;

    private final ItemStackHandler items = new ItemStackHandler(4) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

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

    public ItemStackHandler getItems() {
        return items;
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
        ItemStack input = items.getStackInSlot(SLOT_INPUT);
        ItemStack shard = items.getStackInSlot(SLOT_SHARD);
        if (input.isEmpty() || !shard.is(ModRegistry.QUANTUM_SHARD.get())) {
            return false;
        }
        if (!items.getStackInSlot(SLOT_OUT_A).isEmpty() || !items.getStackInSlot(SLOT_OUT_B).isEmpty()) {
            return false;
        }
        if (input.getMaxStackSize() <= 1) {
            return false;
        }
        QuantumLinkData link = input.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null) {
            return true;
        }
        QuantumNetworks.Network network = QuantumNetworks.get(level.getServer()).network(link.networkId());
        return network != null && network.aliveMembers.size() < QuantumNetworks.MAX_MEMBERS;
    }

    private void entangle(ServerLevel level, BlockPos pos) {
        ItemStack input = items.getStackInSlot(SLOT_INPUT);
        QuantumNetworks networks = QuantumNetworks.get(level.getServer());
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
        } else {
            // Expansion: the window passes through, a new window of the same network appears.
            int member = networks.addMember(link.networkId());
            if (member < 0) {
                return;
            }
            QuantumNetworks.Network network = networks.network(link.networkId());
            outA = input.copy();
            outA.setCount(network.pool);
            outB = input.copy();
            outB.setCount(network.pool);
            outB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(link.networkId(), member));
        }

        items.setStackInSlot(SLOT_INPUT, ItemStack.EMPTY);
        items.getStackInSlot(SLOT_SHARD).shrink(1);
        items.setStackInSlot(SLOT_OUT_A, outA);
        items.setStackInSlot(SLOT_OUT_B, outB);
        level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.8f, 1.4f);
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int slot = 0; slot < items.getSlots(); slot++) {
            ItemStack stack = items.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                items.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("items", items.serializeNBT(registries));
        tag.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.deserializeNBT(registries, tag.getCompound("items"));
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
