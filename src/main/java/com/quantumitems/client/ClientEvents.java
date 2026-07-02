package com.quantumitems.client;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class ClientEvents {
    private ClientEvents() {
    }

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent event) {
            event.register(ModRegistry.QUANTUM_ENTANGLER_MENU.get(), QuantumEntanglerScreen::new);
        }
    }

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onItemTooltip(ItemTooltipEvent event) {
            QuantumLinkData link = event.getItemStack().get(ModRegistry.QUANTUM_LINK.get());
            if (link != null) {
                int insertAt = Math.min(1, event.getToolTip().size());
                event.getToolTip().add(insertAt, link.tooltipLine());
            }
        }
    }
}
