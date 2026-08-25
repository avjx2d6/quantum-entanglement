package com.quantumitems.client;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * A spin angle that survives a change of speed.
 *
 * <p>Taking the angle as {@code time * speed} looks right only while the speed
 * never changes. The observer's does — it idles, wakes with the ritual and
 * winds up through the crescendo — and at every one of those boundaries the
 * angle jumps by {@code time * delta}, which at world ages of millions of ticks
 * is an arbitrary teleport, not a nudge. That is the visible snap at each stage.
 *
 * <p>So the angle is integrated instead: each frame it advances by the current
 * speed times the elapsed time. The speed itself is eased toward its target
 * rather than set, so the observer accelerates into the crescendo instead of
 * stepping into it, and nothing is ever discontinuous.
 */
public final class SpinClock {

    /** Fraction of the remaining speed error closed per tick. */
    private static final float EASE = 0.09f;
    /** A lag spike or a chunk reload must not spin the thing like a top. */
    private static final float MAX_STEP_TICKS = 4.0f;
    /** Slower than the spin: the observer lights up like something charging. */
    private static final float GLOW_EASE = 0.030f;

    private static final Map<BlockPos, SpinClock> CLOCKS = new HashMap<>();

    private float angle;
    private float speed;
    private float glow;
    private float lastStep;
    private float lastTime = Float.NaN;

    private SpinClock() {
    }

    /** Leaving a world: these hold a position key per core and resonator. */
    public static void forgetAll() {
        CLOCKS.clear();
    }

    /**
     * @param time  game time plus partial tick
     * @param target degrees per tick the spin is heading for
     * @return the accumulated angle in degrees
     */
    public static float advance(BlockPos pos, float time, float target) {
        SpinClock clock = CLOCKS.computeIfAbsent(pos.immutable(), p -> new SpinClock());
        if (Float.isNaN(clock.lastTime)) {
            clock.lastTime = time;
            clock.speed = target;
        }
        float step = Math.min(MAX_STEP_TICKS, Math.max(0, time - clock.lastTime));
        clock.lastTime = time;
        clock.lastStep = step;
        // frame-rate independent approach to the target speed
        clock.speed += (target - clock.speed) * (1 - (float) Math.pow(1 - EASE, step));
        clock.angle = (clock.angle + clock.speed * step) % 360.0f;
        return clock.angle;
    }

    /**
     * How lit the observer is, 0 for the world's own light and 1 for fullbright.
     *
     * <p>Waking it used to be a ternary, so the gem snapped to fullbright the
     * instant the ritual started and snapped back the instant it ended. Eased
     * on the same clock as the spin, and slower, so it comes up like something
     * being charged.
     *
     * <p>Advance the spin first: this shares that entry's step, and only the
     * spin moves the clock forward.
     */
    public static float glow(BlockPos pos, float target) {
        SpinClock clock = CLOCKS.get(pos.immutable());
        if (clock == null) {
            return target;
        }
        // Per TICK, off the step the spin just measured — easing per frame
        // would light the gem twice as fast at sixty frames as at thirty.
        clock.glow += (target - clock.glow) * (1 - (float) Math.pow(1 - GLOW_EASE, clock.lastStep));
        return clock.glow;
    }

    /**
     * Blends a packed light value toward fullbright. Block and sky light are
     * separate 0..15 fields inside the packed int, so they have to be pulled
     * apart, moved and put back — lerping the packed value itself would run
     * one field's carry into the other.
     */
    public static int lit(int packed, float glow) {
        int block = Math.round(Mth.lerp(glow, net.minecraft.client.renderer.LightTexture.block(packed), 15));
        int sky = Math.round(Mth.lerp(glow, net.minecraft.client.renderer.LightTexture.sky(packed), 15));
        return net.minecraft.client.renderer.LightTexture.pack(block, sky);
    }
}
