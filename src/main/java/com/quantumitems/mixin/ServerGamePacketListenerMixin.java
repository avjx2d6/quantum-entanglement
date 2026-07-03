package com.quantumitems.mixin;

import com.quantumitems.ServerEvents;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The creative screen is client-authoritative: it splits and rearranges
 * stacks locally and uploads raw slot contents. Reconciling the inventory
 * immediately after each creative slot packet keeps windows consistent
 * without waiting for the periodic sweep.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("TAIL"))
    private void quantumitems$afterCreativeSlotSet(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine != null) {
            ServerEvents.sweepPlayer(engine, this.player);
        }
    }
}
