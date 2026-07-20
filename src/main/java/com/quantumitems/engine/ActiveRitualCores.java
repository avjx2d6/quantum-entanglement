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
 */
public final class ActiveRitualCores {
    private static final double RADIUS_SQ = 8.5 * 8.5;
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

    public static boolean nearActiveCore(Level level, Vec3 position) {
        Set<BlockPos> set = CORES.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (BlockPos pos : set) {
            if (position.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }
}
