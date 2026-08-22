package com.quantumitems;

import com.mojang.brigadier.CommandDispatcher;
import com.quantumitems.block.QuantumCoreBlockEntity;
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
                        .executes(ctx -> listNetworks(ctx.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> removeNetwork(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("ritual")
                        .then(Commands.literal("hold")
                                .then(Commands.literal("connecting").executes(ctx -> hold(ctx.getSource(),
                                        QuantumCoreBlockEntity.Phase.CONNECTING)))
                                .then(Commands.literal("scanning").executes(ctx -> hold(ctx.getSource(),
                                        QuantumCoreBlockEntity.Phase.SCANNING)))
                                .then(Commands.literal("judgement").executes(ctx -> hold(ctx.getSource(),
                                        QuantumCoreBlockEntity.Phase.JUDGEMENT)))
                                .then(Commands.literal("crescendo").executes(ctx -> hold(ctx.getSource(),
                                        QuantumCoreBlockEntity.Phase.CRESCENDO))))
                        .then(Commands.literal("release").executes(ctx -> release(ctx.getSource())))));
    }

    /** The nearest core within 24 blocks of the caller — the one being looked at while tuning. */
    private static QuantumCoreBlockEntity nearestCore(CommandSourceStack src) {
        net.minecraft.world.phys.Vec3 at = src.getPosition();
        net.minecraft.core.BlockPos origin = net.minecraft.core.BlockPos.containing(at);
        QuantumCoreBlockEntity best = null;
        double bestSq = 24 * 24;
        for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                origin.offset(-24, -12, -24), origin.offset(24, 12, 24))) {
            if (src.getLevel().getBlockEntity(pos) instanceof QuantumCoreBlockEntity core) {
                double sq = pos.distToCenterSqr(at);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = core;
                }
            }
        }
        return best;
    }

    private static int hold(CommandSourceStack src, QuantumCoreBlockEntity.Phase phase) {
        QuantumCoreBlockEntity core = nearestCore(src);
        if (core == null) {
            src.sendFailure(Component.literal("No quantum core within 24 blocks."));
            return 0;
        }
        core.holdForTuning(phase);
        src.sendSuccess(() -> Component.literal("Core at " + core.getBlockPos().toShortString()
                + " held in " + phase + " — /quantum ritual release to let it go")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int release(CommandSourceStack src) {
        QuantumCoreBlockEntity core = nearestCore(src);
        if (core == null) {
            src.sendFailure(Component.literal("No quantum core within 24 blocks."));
            return 0;
        }
        core.releaseFromTuning();
        src.sendSuccess(() -> Component.literal("Core released; the ritual clock runs again.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int setDebug(CommandSourceStack src, boolean on) {
        QuantumDebug.setEnabled(on);
        src.sendSuccess(() -> Component.literal("Quantum debug " + (on ? "ON" : "OFF"))
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        return 1;
    }

    /**
     * Removes a network by id — the cleanup tool for ghost networks (pool > 0
     * with no live windows, left behind by old bugs). Any live windows of the
     * network are emptied honestly via dissolve, so this is safe on real
     * networks too (it destroys the pooled items, like an admin /clear).
     */
    private static int removeNetwork(CommandSourceStack src, int id) {
        MinecraftServer server = src.getServer();
        QuantumNetworks networks = QuantumNetworks.get(server);
        QuantumNetworks.Network network = networks.network(id);
        if (network == null) {
            src.sendFailure(Component.literal("No network #" + id));
            return 0;
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        int pool = network.pool;
        if (engine != null) {
            engine.dissolve(id, network, networks);
        } else {
            networks.removeNetwork(id);
        }
        src.sendSuccess(() -> Component.literal("Removed network #" + id + " (pool was " + pool + ")")
                .withStyle(ChatFormatting.YELLOW), true);
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
