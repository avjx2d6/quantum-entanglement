package com.quantumitems.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Port of Create's SyncedBlockEntity (MIT). The load-bearing detail is the
 * explicit {@link #onDataPacket} override: NeoForge's default skips EMPTY
 * update tags, and a block entity whose synced state just became "nothing"
 * (a pedestal cleared of its item) produces exactly that — so the client
 * kept rendering a stack the server no longer had. Applying the tag
 * unconditionally, empty or not, is what makes clearing sync.
 */
public abstract class SyncedBlockEntity extends BlockEntity {
    public SyncedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        loadAdditional(tag == null ? new CompoundTag() : tag, registries);
    }

    public void sendData() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(getBlockPos());
        }
    }

    public void notifyUpdate() {
        setChanged();
        sendData();
    }
}
