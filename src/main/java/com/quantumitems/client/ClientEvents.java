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

        /**
         * The shard is drawn as a living knot rather than a block model. Vanilla
         * only asks for a custom renderer when the baked model says so, which is
         * why {@code item/quantum_knot.json} inherits from {@code builtin/entity}.
         */
        @SubscribeEvent
        public static void registerClientExtensions(
                net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
            event.registerItem(new EntangledKnotRenderer.Extensions(), ModRegistry.QUANTUM_KNOT.get());
        }

        @SubscribeEvent
        public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            // Ponder is embedded (jarJar) and a required client dependency, so
            // it is always present. Its own plugins register during this same
            // event and PonderIndex.registerAll() runs later (load-complete),
            // so adding our plugin here is picked up in time.
            event.enqueueWork(() ->
                    net.createmod.ponder.foundation.PonderIndex.addPlugin(
                            new com.quantumitems.client.ponder.QuantumPonderPlugin()));
        }
    }

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {
        /** Leaving a world: drop the client-side hum instances (see {@link RitualHumSound#forgetAll()}). */
        @SubscribeEvent
        public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                RitualHumSound.forgetAll();
                RitualBeamRenderer.forgetAll();
                SpinClock.forgetAll();
            }
        }

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
            // Nothing else gets a tooltip. The four items used to carry a line
            // of flavour apiece — "a splinter of somewhere else", "an eye that
            // opens onto somewhere else" — which told a player nothing and
            // pitched the mod as more cryptic than it is. What the machine does
            // is taught by the embedded Ponder guide; what a window does is on
            // the window, because that is the only one that is not obvious from
            // holding the thing.
        }

        private static net.minecraft.network.chat.Component doctrine(String key) {
            return net.minecraft.network.chat.Component.translatable(key)
                    .withStyle(net.minecraft.ChatFormatting.GRAY);
        }
    }
}
