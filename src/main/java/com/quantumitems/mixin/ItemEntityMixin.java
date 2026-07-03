package com.quantumitems.mixin;

import com.quantumitems.engine.GroundWindowSync;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The stack of an ItemEntity reaches clients only through SynchedEntityData,
 * and plain writes into the stack's count field never mark that data dirty.
 * This exposes a forced sync so ground windows display live pool values.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin implements GroundWindowSync {

    @Shadow
    @Final
    private static EntityDataAccessor<ItemStack> DATA_ITEM;

    @Override
    public void quantumitems$forceItemSync() {
        ItemEntity self = (ItemEntity) (Object) this;
        self.getEntityData().set(DATA_ITEM, self.getItem(), true);
    }
}
