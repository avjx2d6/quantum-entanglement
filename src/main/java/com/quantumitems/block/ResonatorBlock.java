package com.quantumitems.block;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
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
 * Hand-only pedestal of the ritual circle, with Create-depot interaction
 * semantics (see SharedDepotBlockMethods#onUse): ONE unified click handler
 * that first hands back whatever lies on the pedestal, then lays down what
 * you held. Empty hand = take, full hand = swap; it never returns FAIL for
 * an occupied pedestal — that would suppress vanilla's useWithoutItem
 * fallback and brick the empty-hand take (the playtest bug).
 *
 * The exchange moves whole ItemStack instances between hand and pedestal —
 * a player gesture by nature: a window travels whole, link intact.
 *
 * Deliberate deviation from the depot: Create only reacts to clicks on the
 * TOP face (ray.getDirection() != UP → PASS). The author finds that
 * annoying in the depot itself, so our pedestal answers on any face.
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
    @Nullable
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (type != com.quantumitems.ModRegistry.RESONATOR_BE.get()) {
            return null;
        }
        // Both sides: the slide animation is Create-depot style, the client
        // advances it between sync packets just like the server does.
        return (tickLevel, pos, tickState, blockEntity) -> ((ResonatorBlockEntity) blockEntity).tick();
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
            return ItemInteractionResult.FAIL; // silent: the theater already says a ritual is running
        }
        boolean emptyHanded = heldStack.isEmpty();
        ItemStack laidOut = resonator.getItem(0);
        if (emptyHanded && laidOut.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Hand ↔ pedestal exchange: what lay there goes INTO THE HAND (not
        // scattered into the inventory), what was held lies down.
        if (!laidOut.isEmpty()) {
            resonator.removeItemNoUpdate(0);
            resonator.setChanged();
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
                    1.0f + level.getRandom().nextFloat());
        }
        if (!emptyHanded) {
            resonator.layDown(heldStack, player.getDirection());
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS, 0.7f, 1.2f);
        }
        player.setItemInHand(hand, laidOut);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ResonatorBlockEntity resonator) {
            net.minecraft.world.Containers.dropContents(level, pos, resonator);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
