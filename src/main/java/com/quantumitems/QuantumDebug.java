package com.quantumitems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Runtime diagnostics for quantum networks. When enabled (via {@code /quantum
 * debug}), every pool mutation is echoed to chat so the mechanic can be traced
 * live in-game — there is no other way to see what the pool authority is doing.
 * Off by default and a no-op when off, so it never costs anything in normal play.
 */
public final class QuantumDebug {
    private static volatile boolean enabled = false;

    private QuantumDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Broadcasts a trace line to chat (and the server log) when debug is on. */
    public static void log(MinecraftServer server, String message) {
        if (!enabled || server == null) {
            return;
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("[Q] ").withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.literal(message).withStyle(ChatFormatting.GRAY)),
                false);
    }
}
