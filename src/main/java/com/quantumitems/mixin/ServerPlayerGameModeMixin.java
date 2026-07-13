package com.quantumitems.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A block right-click is a PLAYER gesture too: taking a window off a Create
 * depot (or similar hand-interaction blocks) goes through mod code calling
 * {@code split} outside any menu — the whole-take must still relocate the
 * window with its link instead of being treated as automation.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @WrapMethod(method = "useItemOn")
    private InteractionResult quantumitems$playerGestureScope(ServerPlayer player, Level level, ItemStack stack,
                                                              InteractionHand hand, BlockHitResult hitResult,
                                                              Operation<InteractionResult> original) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return original.call(player, level, stack, hand, hitResult);
        }
        engine.beginPlayerGesture();
        try {
            return original.call(player, level, stack, hand, hitResult);
        } finally {
            engine.endPlayerGesture();
        }
    }
}
