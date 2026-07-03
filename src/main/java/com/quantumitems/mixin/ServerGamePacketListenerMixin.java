package com.quantumitems.mixin;

import com.quantumitems.ModRegistry;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The creative screen is client-authoritative: it splits and rearranges
 * stacks locally and uploads raw slot contents. The client runs quantum
 * split semantics locally (partial splits go plain), so an incoming window
 * is treated as a direct edit of its pool.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("TAIL"))
    private void quantumitems$afterCreativeSlotSet(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        QuantumEngine engine = QuantumEngine.onServerThread();
        if (engine == null) {
            return;
        }
        int slot = packet.slotNum();
        if (slot >= 1 && slot < this.player.inventoryMenu.slots.size()) {
            ItemStack inSlot = this.player.inventoryMenu.getSlot(slot).getItem();
            if (inSlot.has(ModRegistry.QUANTUM_LINK.get())) {
                engine.creativeUpdate(inSlot);
            }
        }
    }
}
