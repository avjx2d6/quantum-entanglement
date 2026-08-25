package com.quantumitems.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positions of cores with a ritual in progress, per dimension, reported from
 * the block-entity tick.
 *
 * <p>This is how the beam renderer finds its work. It cannot ask the level for
 * "every core in range" — that would mean walking block entities every frame —
 * and it cannot hang off the cores' own block-entity renderers, because a BER
 * only runs while its own block is on screen and the beams reach two blocks
 * further out than the core does.
 *
 * <p>Membership lasts until the phase returns to IDLE rather than ending at the
 * verdict: both SUCCESS and FAILURE have a second of beam left to draw, and a
 * core dropped at the verdict has them blink out instead of finishing.
 *
 * <p>Reported by both sides. Only the client reads it, but the tick that
 * reports is shared code, and one extra set on the server is cheaper than a
 * side check in the hot path.
 */
public final class ActiveRitualCores {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> CORES = new ConcurrentHashMap<>();

    private ActiveRitualCores() {
    }

    public static void report(Level level, BlockPos pos, boolean active) {
        Set<BlockPos> set = CORES.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        if (active) {
            set.add(pos.immutable());
        } else {
            set.remove(pos);
        }
    }

    /**
     * Forgets a dimension's cores because its level is going away. Nothing in
     * the level's own lifecycle does this: a client quitting to the title
     * drops its ClientLevel without unloading chunks, so the block entities
     * never report themselves inactive and the positions would haunt the next
     * world opened under the same dimension key.
     */
    public static void clear(ResourceKey<Level> dimension) {
        CORES.remove(dimension);
    }

    /** Forgets everything; the server (and with it every dimension) is gone. */
    public static void clear() {
        CORES.clear();
    }

    /** Every core with beams to draw in this dimension; empty when none. */
    public static Collection<BlockPos> beingDrawn(Level level) {
        Set<BlockPos> set = CORES.get(level.dimension());
        return set == null ? List.of() : set;
    }
}
