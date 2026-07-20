package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Center of the ritual circle: TWO blocks tall, door-pattern (one block,
 * HALF property, the upper half places and breaks together with the lower).
 * The block entity lives in the LOWER half; the upper half is a dumb shell
 * that delegates every interaction down. Right-click with a Quantum Shard
 * launches the ritual on a complete circle; on an unfinished machine the
 * shard just lies on the core, inert and retrievable.
 */
public class QuantumCoreBlock extends Block implements EntityBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    /** Emitted light steps up with the ritual crescendo (0..3 → light 0/5/10/15). */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty GLOW =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("glow", 0, 3);

    private static final VoxelShape SHAPE_LOWER = Shapes.or(
            Block.box(0, 0, 0, 16, 3, 16),
            Block.box(1, 3, 1, 4, 14, 4), Block.box(12, 3, 1, 15, 14, 4),
            Block.box(1, 3, 12, 4, 14, 15), Block.box(12, 3, 12, 15, 14, 15),
            Block.box(1, 14, 1, 15, 16, 15));
    private static final VoxelShape SHAPE_UPPER = Shapes.or(
            Block.box(1, 0, 1, 15, 1, 15),
            Block.box(2, 1, 2, 4, 12, 4), Block.box(12, 1, 2, 14, 12, 4),
            Block.box(2, 1, 12, 4, 12, 14), Block.box(12, 1, 12, 14, 12, 14),
            Block.box(1, 12, 1, 15, 15, 15));

    public QuantumCoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(GLOW, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, GLOW);
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? SHAPE_LOWER : SHAPE_UPPER;
    }

    // --- door-pattern placement and integrity ---

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() < level.getMaxBuildHeight() - 1 && level.getBlockState(pos.above()).canBeReplaced(context)) {
            return defaultBlockState();
        }
        return null; // no headroom for the upper half
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            return neighborState.is(this) && neighborState.getValue(HALF) != half
                    ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative-mode courtesy from vanilla doors: breaking the upper half
        // removes the lower silently instead of popping a drop.
        if (!level.isClientSide && player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (belowState.is(this) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, below, Block.getId(belowState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // --- block entity: LOWER half only, upper delegates ---

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new QuantumCoreBlockEntity(pos, state) : null;
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModRegistry.QUANTUM_CORE_BE.get() || state.getValue(HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        // Both sides: the client tick only advances the phase clock that
        // drives the shard-spin animation; all logic stays server-side.
        return (tickLevel, pos, tickState, blockEntity) ->
                QuantumCoreBlockEntity.tick(tickLevel, pos, tickState, (QuantumCoreBlockEntity) blockEntity);
    }

    @Nullable
    private QuantumCoreBlockEntity coreAt(Level level, BlockPos pos, BlockState state) {
        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        return level.getBlockEntity(lowerPos) instanceof QuantumCoreBlockEntity core ? core : null;
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
        QuantumCoreBlockEntity core = coreAt(level, pos, state);
        if (core == null) {
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
        if (!state.is(newState.getBlock()) && state.getValue(HALF) == DoubleBlockHalf.LOWER
                && level.getBlockEntity(pos) instanceof QuantumCoreBlockEntity core
                && !core.displayedShard().isEmpty()) {
            level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    core.displayedShard()));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
