package com.quantumitems.client;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class ClientEvents {
    private ClientEvents() {
    }

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void registerRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(ModRegistry.RESONATOR_BE.get(), ResonatorRenderer::new);
            event.registerBlockEntityRenderer(ModRegistry.QUANTUM_CORE_BE.get(), QuantumCoreRenderer::new);
        }

        @SubscribeEvent
        public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            // Ponder is an optional dependency: only wire the guide up if it is
            // present. Its own plugins register during this same event and
            // PonderIndex.registerAll() runs later (load-complete), so adding
            // our plugin here is picked up in time.
            if (!net.neoforged.fml.ModList.get().isLoaded("ponder")) {
                return;
            }
            event.enqueueWork(() ->
                    net.createmod.ponder.foundation.PonderIndex.addPlugin(
                            new com.quantumitems.client.ponder.QuantumPonderPlugin()));
        }
    }

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onItemTooltip(ItemTooltipEvent event) {
            var stack = event.getItemStack();
            var tooltip = event.getToolTip();
            QuantumLinkData link = stack.get(ModRegistry.QUANTUM_LINK.get());
            if (link != null) {
                int insertAt = Math.min(1, tooltip.size());
                tooltip.add(insertAt, link.tooltipLine());
                if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
                    tooltip.add(doctrine("tooltip.quantumitems.window.shared"));
                    tooltip.add(doctrine("tooltip.quantumitems.window.ground"));
                    tooltip.add(doctrine("tooltip.quantumitems.window.automation"));
                    tooltip.add(doctrine("tooltip.quantumitems.window.shulker"));
                } else {
                    tooltip.add(insertAt + 1, net.minecraft.network.chat.Component
                            .translatable("tooltip.quantumitems.window.hint")
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                }
                return;
            }
            if (stack.is(ModRegistry.QUANTUM_SHARD.get())) {
                tooltip.add(doctrine("tooltip.quantumitems.shard.lore"));
                tooltip.add(doctrine("tooltip.quantumitems.shard.source"));
            }
            // Core/resonator/eye tooltips deliberately absent for now: the
            // author wants mechanics corrected first, labels designed later.
        }

        private static net.minecraft.network.chat.Component doctrine(String key) {
            return net.minecraft.network.chat.Component.translatable(key)
                    .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC);
        }
    }
}
