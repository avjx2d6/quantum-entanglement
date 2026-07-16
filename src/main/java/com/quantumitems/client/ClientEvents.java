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
            } else if (stack.is(ModRegistry.QUANTUM_CORE_ITEM.get())) {
                tooltip.add(doctrine("tooltip.quantumitems.core.usage"));
                tooltip.add(doctrine("tooltip.quantumitems.core.structure"));
            } else if (stack.is(ModRegistry.RESONATOR_ITEM.get())) {
                tooltip.add(doctrine("tooltip.quantumitems.resonator.usage"));
            } else if (stack.is(ModRegistry.ENTANGLED_EYE.get())) {
                tooltip.add(doctrine("tooltip.quantumitems.entangled_eye.lore"));
            }
        }

        private static net.minecraft.network.chat.Component doctrine(String key) {
            return net.minecraft.network.chat.Component.translatable(key)
                    .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC);
        }
    }
}
