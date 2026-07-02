package com.quantumitems.menu;

import com.quantumitems.ModRegistry;
import com.quantumitems.block.QuantumEntanglerBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class QuantumEntanglerMenu extends AbstractContainerMenu {
    private static final int MACHINE_SLOTS = 4;
    private static final int PLAYER_INV_END = MACHINE_SLOTS + 36;

    private final QuantumEntanglerBlockEntity blockEntity;
    private final ContainerData data;

    public static QuantumEntanglerMenu fromNetwork(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        QuantumEntanglerBlockEntity blockEntity =
                (QuantumEntanglerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos());
        return new QuantumEntanglerMenu(containerId, playerInventory, blockEntity, new SimpleContainerData(2));
    }

    public QuantumEntanglerMenu(int containerId, Inventory playerInventory,
                                QuantumEntanglerBlockEntity blockEntity, ContainerData data) {
        super(ModRegistry.QUANTUM_ENTANGLER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.data = data;

        IItemHandler items = blockEntity.getItems();
        this.addSlot(new SlotItemHandler(items, QuantumEntanglerBlockEntity.SLOT_INPUT, 35, 21) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getMaxStackSize() > 1;
            }
        });
        this.addSlot(new SlotItemHandler(items, QuantumEntanglerBlockEntity.SLOT_SHARD, 35, 51) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModRegistry.QUANTUM_SHARD.get());
            }
        });
        this.addSlot(new OutputSlot(items, QuantumEntanglerBlockEntity.SLOT_OUT_A, 125, 21));
        this.addSlot(new OutputSlot(items, QuantumEntanglerBlockEntity.SLOT_OUT_B, 125, 51));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        this.addDataSlots(data);
    }

    public int getProgress() {
        return data.get(0);
    }

    public int getProcessTime() {
        int processTime = data.get(1);
        return processTime > 0 ? processTime : QuantumEntanglerBlockEntity.PROCESS_TIME;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < MACHINE_SLOTS) {
            if (!this.moveItemStackTo(stack, MACHINE_SLOTS, PLAYER_INV_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved;
            if (stack.is(ModRegistry.QUANTUM_SHARD.get())) {
                moved = this.moveItemStackTo(stack, QuantumEntanglerBlockEntity.SLOT_SHARD, QuantumEntanglerBlockEntity.SLOT_SHARD + 1, false)
                        || this.moveItemStackTo(stack, QuantumEntanglerBlockEntity.SLOT_INPUT, QuantumEntanglerBlockEntity.SLOT_INPUT + 1, false);
            } else {
                moved = this.moveItemStackTo(stack, QuantumEntanglerBlockEntity.SLOT_INPUT, QuantumEntanglerBlockEntity.SLOT_INPUT + 1, false);
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity != null && !blockEntity.isRemoved()
                && player.distanceToSqr(blockEntity.getBlockPos().getCenter()) <= 64.0;
    }

    private static class OutputSlot extends SlotItemHandler {
        OutputSlot(IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
