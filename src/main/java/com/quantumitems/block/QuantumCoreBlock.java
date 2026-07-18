package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Center of the ritual circle. Right-click with a Quantum Shard launches the
 * ritual immediately — provided the structure is built; the only "free"
 * refusal is a machine that does not exist yet. Everything after launch is
 * the core's state machine.
 */
public class QuantumCoreBlock extends Block implements EntityBlock {
    public QuantumCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuantumCoreBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModRegistry.QUANTUM_CORE_BE.get()) {
            return null;
        }
        // Both sides: the client tick only advances the phase clock that
        // drives the shard-spin animation; all logic stays server-side.
        return (tickLevel, pos, tickState, blockEntity) ->
                QuantumCoreBlockEntity.tick(tickLevel, pos, tickState, (QuantumCoreBlockEntity) blockEntity);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldStack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        boolean emptyHanded = heldStack.isEmpty();
        if (!emptyHanded && !heldStack.is(ModRegistry.QUANTUM_SHARD.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof QuantumCoreBlockEntity core)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (core.isRitualRunning()) {
            return ItemInteractionResult.FAIL; // silent: the theater speaks for itself
        }
        if (emptyHanded) {
            ItemStack idleShard = core.takeShard();
            if (idleShard.isEmpty()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            player.setItemInHand(hand, idleShard);
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.2f, 1.0f + level.getRandom().nextFloat());
            return ItemInteractionResult.SUCCESS;
        }
        return core.placeShard(heldStack) ? ItemInteractionResult.SUCCESS : ItemInteractionResult.FAIL;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof QuantumCoreBlockEntity core
                && !core.displayedShard().isEmpty()) {
            level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    core.displayedShard()));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
