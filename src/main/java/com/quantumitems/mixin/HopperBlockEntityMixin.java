package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The pure-vanilla hopper paths (entity containers like chest minecarts —
 * block containers go through the NeoForge hooks) remove one item and, on a
 * failed insert, "restore" by setting the old count back on the source stack.
 * For a partial pool that actually composes with the engine (the +1 delta
 * refills what the discarded item took), but moving the LAST item is a
 * window move: the restore would revive a plain husk while the real window
 * gets discarded — a dupe. Bounce the window home instead and report the
 * attempt as handled.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Redirect(method = "tryTakeInItemFromSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack quantumitems$pullAddItem(Container source, Container destination, ItemStack extracted, Direction direction,
                                                      Hopper hopper, Container container, int slot, Direction methodDirection) {
        ItemStack leftover = HopperBlockEntity.addItem(source, destination, extracted, direction);
        if (!leftover.isEmpty() && leftover.has(ModRegistry.QUANTUM_LINK.get())) {
            container.setItem(slot, leftover);
            return ItemStack.EMPTY; // "success": skips the husk-restore branch
        }
        return leftover;
    }

    @Redirect(method = "ejectItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack quantumitems$ejectAddItem(Container source, Container destination, ItemStack extracted, Direction direction,
                                                       Level level, BlockPos pos, HopperBlockEntity hopper) {
        ItemStack leftover = HopperBlockEntity.addItem(source, destination, extracted, direction);
        if (!leftover.isEmpty() && leftover.has(ModRegistry.QUANTUM_LINK.get())) {
            for (int i = 0; i < hopper.getContainerSize(); i++) {
                if (hopper.getItem(i).isEmpty()) {
                    hopper.setItem(i, leftover);
                    return ItemStack.EMPTY;
                }
            }
        }
        return leftover;
    }
}
