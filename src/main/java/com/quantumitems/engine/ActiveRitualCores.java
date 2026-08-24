package com.quantumitems.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positions of cores currently running a ritual, per dimension, reported by
 * BOTH sides' block-entity ticks. Pure data, no client classes — so the
 * ExperienceOrb mixin can consult it on either side. This is what lets the
 * CLIENT know an orb is claimed: entity command tags do not sync, and the
 * client's own orb simulation kept steering orbs toward the player (the
 * jitter-and-rubber-band bug).
 *
 * <p>Two memberships, because they answer different questions and stopped
 * agreeing the moment failure got an animation. A core that has just failed
 * must not still be dragging experience orbs toward itself — it released them
 * on the way in — but its beams have a second of dying left to draw. One set
 * for the pull, one for the drawing.
 */
public final class ActiveRitualCores {
    private static final double RADIUS_SQ = 8.5 * 8.5;
    /** Cores whose ritual is live, and so are claiming nearby experience. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PULLING = new ConcurrentHashMap<>();
    /** Cores the beam renderer still has something to draw for. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> DRAWN = new ConcurrentHashMap<>();

    private ActiveRitualCores() {
    }

    private static void put(Map<ResourceKey<Level>, Set<BlockPos>> map,
                            Level level, BlockPos pos, boolean member) {
        Set<BlockPos> set = map.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        if (member) {
            set.add(pos.immutable());
        } else {
            set.remove(pos);
        }
    }

    public static void report(Level level, BlockPos pos, boolean pulling, boolean drawn) {
        put(PULLING, level, pos, pulling);
        put(DRAWN, level, pos, drawn);
    }

    /**
     * Forgets a dimension's cores because its level is going away. Nothing in
     * the level's own lifecycle does this: a client quitting to the title
     * drops its ClientLevel without unloading chunks, so the block entities
     * never report themselves inactive and the positions would haunt the next
     * world opened under the same dimension key — steering its orbs at empty
     * air forever.
     */
    public static void clear(ResourceKey<Level> dimension) {
        PULLING.remove(dimension);
        DRAWN.remove(dimension);
    }

    /** Forgets everything; the server (and with it every dimension) is gone. */
    public static void clear() {
        PULLING.clear();
        DRAWN.clear();
    }

    /** Every core with beams left to draw in this dimension; empty when none. */
    public static java.util.Collection<BlockPos> beingDrawn(Level level) {
        Set<BlockPos> set = DRAWN.get(level.dimension());
        return set == null ? java.util.List.of() : set;
    }

    /** The closest running core within pull radius, or null. */
    @javax.annotation.Nullable
    public static BlockPos nearestActiveCore(Level level, Vec3 position) {
        Set<BlockPos> set = PULLING.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        double bestSq = RADIUS_SQ;
        for (BlockPos pos : set) {
            double sq = position.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos;
            }
        }
        return best;
    }
}
