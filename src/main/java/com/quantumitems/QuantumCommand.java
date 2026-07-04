package com.quantumitems;

import com.mojang.brigadier.CommandDispatcher;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

/**
 * {@code /quantum} operator command: toggles the {@link QuantumDebug} chat trace
 * and lists every live network (id, item, pool, members, and whether each member
 * still has a tracked live instance) — the direct way to check the suspicion
 * that a network lingers after it should be gone.
 */
public final class QuantumCommand {
    private QuantumCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quantum")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("debug")
                        .executes(ctx -> setDebug(ctx.getSource(), !QuantumDebug.isEnabled()))
                        .then(Commands.literal("on").executes(ctx -> setDebug(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setDebug(ctx.getSource(), false))))
                .then(Commands.literal("networks")
                        .executes(ctx -> listNetworks(ctx.getSource()))));
    }

    private static int setDebug(CommandSourceStack src, boolean on) {
        QuantumDebug.setEnabled(on);
        src.sendSuccess(() -> Component.literal("Quantum debug " + (on ? "ON" : "OFF"))
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        return 1;
    }

    private static int listNetworks(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumEngine engine = QuantumEngine.onServerThread();
        Map<Integer, QuantumNetworks.Network> all = networks.all();
        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No quantum networks.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(all.size() + " quantum network(s):").withStyle(ChatFormatting.AQUA), false);
        all.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            int id = entry.getKey();
            QuantumNetworks.Network network = entry.getValue();
            StringBuilder members = new StringBuilder();
            for (int member : network.aliveMembers) {
                boolean live = engine != null && engine.hasLiveInstance(id, member);
                members.append('m').append(member).append(live ? "(live)" : "(none)").append(' ');
            }
            String item = BuiltInRegistries.ITEM.getKey(network.item).toString();
            String line = "  #" + id + "  " + item + "  pool=" + network.pool
                    + "  [" + members.toString().trim() + "]";
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        });
        src.sendSuccess(() -> Component.literal("  (live = instance tracked, none = no live instance)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return all.size();
    }
}
