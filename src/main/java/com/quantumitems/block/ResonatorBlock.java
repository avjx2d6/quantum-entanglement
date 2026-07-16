package com.quantumitems.block;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Hand-only pedestal of the ritual circle. Right-click with a stack lays the
 * whole stack down (windows travel whole — a player gesture); right-click
 * with an empty hand takes it back whole. While the core runs a ritual the
 * circle is locked: nothing goes in or out.
 */
public class ResonatorBlock extends Block implements EntityBlock {
    public ResonatorBlock(Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ResonatorBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldStack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ResonatorBlockEntity resonator)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (resonator.isLockedByRitual()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.quantumitems.ritual_locked"), true);
            return ItemInteractionResult.FAIL;
        }
        if (!resonator.isEmpty()) {
            return ItemInteractionResult.FAIL; // occupied: take with an empty hand first
        }
        resonator.setItem(0, heldStack);
        player.setItemInHand(hand, ItemStack.EMPTY);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS, 0.7f, 1.2f);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ResonatorBlockEntity resonator) || resonator.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (resonator.isLockedByRitual()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.quantumitems.ritual_locked"), true);
            return InteractionResult.FAIL;
        }
        ItemStack stack = resonator.removeItemNoUpdate(0);
        resonator.setChanged();
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 0.7f, 0.9f);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ResonatorBlockEntity resonator) {
            net.minecraft.world.Containers.dropContents(level, pos, resonator);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
