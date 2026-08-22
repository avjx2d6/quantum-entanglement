package com.quantumitems.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.quantumitems.QuantumItemsMod;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.function.Consumer;

/**
 * {@code /qbeam} — client-side tuning for the ritual beams. Client-side on
 * purpose: chat draws over the world, so a value can be changed while a ritual
 * is running and the result is visible in the same breath. Nothing here touches
 * the server, so it works in singleplayer and on someone else's server alike.
 */
@EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
public final class BeamTuningCommand {

    private BeamTuningCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("qbeam")
                .executes(ctx -> report(ctx.getSource()));

        for (BeamTuning.Style style : BeamTuning.Style.values()) {
            root.then(Commands.literal(style.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(ctx -> {
                        BeamTuning.applyPreset(style);
                        return report(ctx.getSource());
                    }));
        }

        root.then(floatArg("amp", 0f, 4f, v -> BeamTuning.amplitude = v));
        root.then(floatArg("speed", 0.05f, 6f, v -> BeamTuning.waveSpeed = v));
        root.then(floatArg("decay", 0.1f, 0.97f, v -> BeamTuning.decay = v));
        root.then(floatArg("spread", 0.02f, 6f, v -> BeamTuning.spread = v));
        root.then(floatArg("width", 0.005f, 0.4f, v -> BeamTuning.width = v));
        root.then(Commands.literal("nodes")
                .then(Commands.argument("v", IntegerArgumentType.integer(3, 64))
                        .executes(ctx -> {
                            BeamTuning.nodes = IntegerArgumentType.getInteger(ctx, "v");
                            return report(ctx.getSource());
                        })));
        root.then(Commands.literal("glow")
                .then(Commands.argument("v", BoolArgumentType.bool())
                        .executes(ctx -> {
                            BeamTuning.glow = BoolArgumentType.getBool(ctx, "v");
                            return report(ctx.getSource());
                        })));

        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> floatArg(
            String name, float min, float max, Consumer<Float> apply) {
        return Commands.literal(name)
                .then(Commands.argument("v", FloatArgumentType.floatArg(min, max))
                        .executes(ctx -> {
                            apply.accept(FloatArgumentType.getFloat(ctx, "v"));
                            return report(ctx.getSource());
                        }));
    }

    private static int report(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("[beam] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(BeamTuning.describe()).withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }
}
