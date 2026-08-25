package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Vector3f;

/**
 * The shard drawn as a knot of the same living strand the ritual beams are made
 * of, instead of as a block model.
 *
 * <p>The curve is a trefoil — the (2,3) torus knot: one closed strand with no
 * beginning or end that passes through itself and cannot be untied without
 * being cut. It is the mod's own premise as an object, and unlike a random clew
 * it is the SAME shape every time, so it stays recognisable in a hotbar.
 *
 * <p>Reached through {@link IClientItemExtensions#getCustomRenderer()}, which
 * vanilla only consults when the baked model reports {@code isCustomRenderer()}
 * — so the item's model JSON has to inherit from {@code builtin/entity}. That
 * one hook covers every context at once: inventory, hand, floor, item frame,
 * and the {@code renderStatic} call that puts the shard above the core.
 *
 * <p>The writhe is a single walk shared by every copy on screen, advanced once
 * per client tick no matter how many knots are being drawn. That is both the
 * cheap way and the right-looking one: every shard in the world twitches in
 * step, which reads as one object seen several times rather than as N separate
 * ones.
 */
public final class EntangledKnotRenderer extends BlockEntityWithoutLevelRenderer {

    // ---- colour: thin film, not a paint job ----

    /**
     * The knot has no colour of its own. Its hue comes from the angle the
     * surface is seen at, the way oil on water or the inside of a shell does,
     * so it crawls across the strand as the knot turns instead of sitting
     * still. That is also what keeps it from reading as any of the three beam
     * colours: those are flat and this one never is.
     *
     * <p>The arc runs green → cyan → violet → magenta and stops there. Taking
     * it the whole way round the wheel would pick up yellows and reds and the
     * thing would read as a novelty rainbow rather than as a film.
     */
    private static final float HUE_MIN = 0.38f;         // ~137°, green
    private static final float HUE_MAX = 0.88f;         // ~317°, magenta
    /**
     * Held perceptually even along the whole arc. At one fixed saturation the
     * hues are nowhere near equally bright — pure green carries three quarters
     * of the luma and pure blue about seven percent — so half the knot went
     * dark and simply vanished in a 16-pixel slot. Saturation is solved per hue
     * to land on this luma instead.
     *
     * <p>The ceiling is what decides whether the knot reads as DEEP or as
     * WASHED OUT, and it is not the same knob as how hard the thing emits.
     * Scaling emission takes every channel down together and the strand goes
     * see-through; raising saturation and paying for it in value takes the
     * off-hue channels down and leaves the dominant one, so the same amount of
     * light arrives as a colour instead of as a glow. At 0.95 and this luma the
     * arc runs #056620 through #045F5F to #0C4EF7 and #FD0CBA — deep the whole
     * way, where 0.85 gave pastels like #5685FF.
     */
    private static final float TARGET_LUMA = 0.30f;
    private static final float MAX_SATURATION = 0.95f;
    /** Split between the view-angle term and the band travelling along the strand. */
    private static final float VIEW_WEIGHT = 0.55f;
    private static final float BAND_WEIGHT = 1.0f - VIEW_WEIGHT;
    /** Bands around the loop. Two, because the strand itself winds around twice. */
    private static final float BANDS = 2.0f;
    /** Ticks for the film to crawl one full arc on a knot nobody is turning. */
    private static final float DRIFT_PERIOD = 130.0f;

    /** The hue arc, resolved once — this is sampled per ring vertex per frame. */
    private static final int[] RAMP = new int[64];

    static {
        for (int i = 0; i < RAMP.length; i++) {
            float h = HUE_MIN + (HUE_MAX - HUE_MIN) * (i / (float) (RAMP.length - 1));
            // At value 1 the luma of a hue falls linearly with saturation, from
            // white down to the pure hue's own luma — so the saturation that
            // hits the target is one division rather than a search. Greens and
            // cyans reach the saturation ceiling while still too bright, and
            // desaturating them further would just bleach them, so the rest of
            // the correction comes off the value instead. Luma is linear in
            // value as well, which makes that a second division.
            float pure = luma(Mth.hsvToRgb(h, 1.0f, 1.0f));
            float s = Mth.clamp((1.0f - TARGET_LUMA) / (1.0f - pure), 0.0f, MAX_SATURATION);
            float v = Mth.clamp(TARGET_LUMA / (1.0f - s * (1.0f - pure)), 0.0f, 1.0f);
            RAMP[i] = Mth.hsvToRgb(h, s, v) & 0xFFFFFF;
        }
    }

    private static float luma(int rgb) {
        return (0.2126f * ((rgb >> 16) & 0xFF)
                + 0.7152f * ((rgb >> 8) & 0xFF)
                + 0.0722f * (rgb & 0xFF)) / 255.0f;
    }

    private static int film(float along, float facing, float drift) {
        float band = 0.5f - 0.5f * Mth.cos((along * BANDS + drift) * Mth.TWO_PI);
        float t = Mth.clamp(VIEW_WEIGHT * (1.0f - facing) + BAND_WEIGHT * band, 0.0f, 1.0f);
        return RAMP[(int) (t * (RAMP.length - 1))];
    }

    /**
     * Half-width of the knot in model space, where 1.0 is a full block. The
     * knot is drawn lying flat, in its own natural pose; every per-context
     * orientation lives in the item model's {@code display} block, so the angle
     * it is seen at can be changed without recompiling. Face-on is where the
     * trefoil is legible, which is what the inventory gets; the world contexts
     * get it tilted, because the core, the floor and item frames all spin it
     * around Y and a flat-on disc would go edge-on once per turn.
     */
    private static final double SIZE = 0.34;

    private static final int NODES = 40;
    private static final int SIDES_WORLD = 6;
    /** In a 16-pixel slot the strand is about a pixel wide; nobody counts faces. */
    private static final int SIDES_GUI = 5;
    /**
     * One gauge everywhere: what reads is the ratio of strand to knot, and it
     * does not change with the display scale. Tried fatter for the inventory —
     * at 0.055 the crossings fill in and the trefoil turns into a pretzel.
     */
    private static final float RADIUS = 0.030f;

    /**
     * Deflection budget. The two closest points of the knot that are far apart
     * ALONG the strand sit 0.189 apart, so a tube fatter than 0.094 would fuse
     * into a blob before any writhe is added. The walk settles at
     * σ = spread/√12 · decay/√(1−decay²) = 0.269 units; at this scale even both
     * strands wandering 3σ straight at each other leaves a 0.11 gap against a
     * 0.06 tube. Past roughly 0.08 the knot starts tying itself shut.
     */
    private static final float WALK_UNIT = 0.050f;      // blocks per unit of walk
    private static final float DECAY = 0.80f;
    private static final float SPREAD = 0.70f;
    /** Ticks for the bright pulse to travel once around the loop. */
    private static final float PULSE_PERIOD = 50.0f;

    // ---- what the knot does while it is being consumed ----

    /**
     * How hard the knot is being worked, 0 in a hand or on the floor and 1 at
     * the end of the crescendo. Set by {@link QuantumCoreRenderer} around its
     * one {@code renderStatic} call and cleared straight after.
     *
     * <p>A static field rather than a parameter because the path in between is
     * vanilla's — the renderer is reached through the baked model, and there is
     * nowhere to thread an argument. Client rendering is single-threaded and
     * the field never outlives the call, so this cannot be read by anything
     * else in between.
     *
     * <p>Everything below is gated on it: an inventory knot is deliberately
     * calm, and none of this shows anywhere but on the core.
     */
    public static float agitation;

    /** A ripple running around the strand. Coherent, unlike the per-node walk. */
    private static final float WAVE = 0.055f;
    /** Turns of the helix around the loop. */
    private static final float WAVE_TURNS = 3.0f;
    /** Ticks for the ripple to travel once around, at full agitation. */
    private static final float WAVE_PERIOD = 14.0f;
    /** Multiplier on the walk when the ritual is at full tilt. */
    private static final float WALK_FURY = 2.4f;

    /** Chords arcing across the knot while it is worked. */
    private static final int ARCS = 5;
    private static final int ARC_NODES = 5;
    private static final float ARC_LIFE = 7.0f;
    private static final float ARC_WIDTH = 0.012f;
    private static final int ARC_COLOR = 0xC8F0FF;   // near white: these are the discharge

    // ---- the fixed curve, and the frame the writhe is applied in ----

    private static final Vec3[] BASE = new Vec3[NODES + 1];
    private static final Vec3[] OFF_U = new Vec3[NODES + 1];
    private static final Vec3[] OFF_V = new Vec3[NODES + 1];

    static {
        for (int i = 0; i <= NODES; i++) {
            double t = 2 * Math.PI * (i % NODES) / NODES;
            double r = 2 + Math.cos(3 * t);
            // y carries sin(3t), so the knot is a disc in XZ with a wave in it
            BASE[i] = new Vec3(r * Math.cos(2 * t), Math.sin(3 * t), r * Math.sin(2 * t))
                    .scale(SIZE / 3.0);
        }
        for (int i = 0; i <= NODES; i++) {
            Vec3 tangent = BASE[(i + 1) % NODES].subtract(BASE[(i - 1 + NODES) % NODES]).normalize();
            // The tangent's y component peaks near 0.83 on this curve, so it is
            // never parallel to up and this cross product is always well formed.
            Vec3 u = tangent.cross(new Vec3(0, 1, 0)).normalize();
            OFF_U[i] = u;
            OFF_V[i] = tangent.cross(u).normalize();
        }
    }

    // ---- the shared writhe ----

    private static final RandomSource RANDOM = RandomSource.create();
    private static final float[][] CUR = new float[NODES][3];   // across u, across v, brightness
    private static final float[][] PREV = new float[NODES][3];
    private static long lastTick = Long.MIN_VALUE;

    private static void advance(long now) {
        if (lastTick == Long.MIN_VALUE) {
            lastTick = now - 1;
        }
        long steps = Math.min(4, now - lastTick);   // a lag spike must not spin the walk
        for (long s = 0; s < steps; s++) {
            for (int i = 0; i < NODES; i++) {
                System.arraycopy(CUR[i], 0, PREV[i], 0, 3);
                CUR[i][0] = (CUR[i][0] + (RANDOM.nextFloat() - 0.5f) * SPREAD) * DECAY;
                CUR[i][1] = (CUR[i][1] + (RANDOM.nextFloat() - 0.5f) * SPREAD) * DECAY;
                CUR[i][2] = (CUR[i][2] + (RANDOM.nextFloat() - 0.5f)) * 0.75f;
            }
        }
        lastTick = now;
    }

    public EntangledKnotRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
        super(dispatcher, models);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        float partial = 0.0f;
        float time = 0.0f;
        if (mc.level != null) {
            advance(mc.level.getGameTime());
            partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
            time = mc.level.getGameTime() + partial;
        }

        Vec3[] pts = new Vec3[NODES + 1];
        float[] bright = new float[NODES + 1];
        float phase = (time / PULSE_PERIOD) % 1.0f;
        float fury = Mth.clamp(agitation, 0, 1);
        float walk = WALK_UNIT * (1 + WALK_FURY * fury);
        for (int i = 0; i < NODES; i++) {
            float a = Mth.lerp(partial, PREV[i][0], CUR[i][0]) * walk;
            float b = Mth.lerp(partial, PREV[i][1], CUR[i][1]) * walk;
            if (fury > 0) {
                float ripple = ((i / (float) NODES) * WAVE_TURNS - time / WAVE_PERIOD) * Mth.TWO_PI;
                a += WAVE * fury * Mth.cos(ripple);
                b += WAVE * fury * Mth.sin(ripple);
            }
            pts[i] = BASE[i].add(OFF_U[i].scale(a)).add(OFF_V[i].scale(b));

            float w = Mth.lerp(partial, PREV[i][2], CUR[i][2]);
            // distance around the loop to the pulse, 0..0.5 either way round
            float d = Math.abs(((i / (float) NODES) - phase + 1.5f) % 1.0f - 0.5f);
            float head = Mth.clamp(1.0f - d * 5.0f, 0.0f, 1.0f);
            // Sized against the additive doubling. Depth is tested but not
            // written, so the far wall of the strand shows through the near one
            // and every pixel is added twice; where the knot crosses itself
            // that becomes four times over. At 0.40 base and 0.52 ramp luma a
            // plain strand lands near 0.42 doubled, a crossing near 0.83, and
            // only the pulse's own peak is allowed to reach white.
            bright[i] = Mth.clamp(0.55f + w * 0.18f + head * head * 0.65f, 0.34f, 1.4f);
        }
        pts[NODES] = pts[0];
        bright[NODES] = bright[0];

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);   // renderByItem starts at the model's corner

        // Which way the eye is, expressed in the knot's own space. positiveZ
        // gives the model-space direction that the pose maps onto view +Z,
        // which is exactly "toward the camera" — and it holds for the GUI's
        // orthographic pass too, flipped Y scale and all.
        Vector3f toCamera = poseStack.last().pose().positiveZ(new Vector3f()).normalize();
        float drift = (time / DRIFT_PERIOD) % 1.0f;
        Vec3 eye = new Vec3(toCamera.x(), toCamera.y(), toCamera.z());

        StrandGeometry.tube(poseStack, buffers.getBuffer(QuantumRenderTypes.RITUAL_BEAM),
                pts, bright, (along, facing) -> film(along, facing, drift), eye, RADIUS,
                context == ItemDisplayContext.GUI ? SIDES_GUI : SIDES_WORLD,
                true);
        if (fury > 0.05f) {
            arcs(poseStack, buffers, pts, time, fury);
        }
        poseStack.popPose();
    }

    /**
     * Discharge jumping straight across the knot from one part of the strand to
     * another. A loop that passes through itself has plenty of pairs of points
     * that are near in space and far apart along the line, and an arc between
     * two of those says what the object is far better than any amount of extra
     * wobble: it is one thing touching itself at a distance.
     *
     * <p>Each arc lives a few ticks and its endpoints come from a hash of its
     * generation, so it is the same arc on every frame of its life and a new
     * one afterwards.
     */
    private static void arcs(PoseStack poseStack, MultiBufferSource buffers,
                             Vec3[] knot, float time, float fury) {
        var consumer = buffers.getBuffer(QuantumRenderTypes.RITUAL_BEAM);
        int live = Math.max(1, Math.round(ARCS * fury));
        for (int arc = 0; arc < live; arc++) {
            float phase = time / ARC_LIFE + arc * 0.37f;
            int generation = Mth.floor(phase);
            float fade = Mth.sin((phase - generation) * Mth.PI);
            if (fade <= 0.05f) {
                continue;
            }
            int seed = generation * 977 + arc * 61;
            int from = hash(seed) % NODES;
            // far enough along the strand that the arc crosses rather than
            // hugging the line it came from
            int to = (from + NODES / 4 + hash(seed + 5) % (NODES / 2)) % NODES;
            Vec3 a = knot[from];
            Vec3 b = knot[to];
            // Bow away from the knot's centre. Two near-opposite points sum to
            // nothing, where normalize has no answer — bow along the chord's
            // own perpendicular instead.
            Vec3 mid = a.add(b).scale(0.5);
            Vec3 bow = mid.lengthSqr() > 1.0e-6
                    ? mid.normalize().scale(0.06 * fury)
                    : b.subtract(a).cross(new Vec3(0, 1, 0)).normalize().scale(0.06 * fury);
            Vec3[] pts = new Vec3[ARC_NODES + 1];
            float[] bright = new float[ARC_NODES + 1];
            for (int i = 0; i <= ARC_NODES; i++) {
                float t = i / (float) ARC_NODES;
                pts[i] = a.add(b.subtract(a).scale(t)).add(bow.scale(Math.sin(t * Math.PI)));
                bright[i] = fade * Mth.sin(t * Mth.PI) * (0.5f + 0.5f * fury);
            }
            StrandGeometry.tube(poseStack, consumer, pts, bright, ARC_COLOR, ARC_WIDTH, 4, false);
        }
    }

    /** Stable non-negative pseudo-random from an integer. */
    private static int hash(int seed) {
        int h = seed * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h >>> 1;
    }

    /**
     * Built on first use rather than at registration: the dispatcher and the
     * model set do not exist yet while client extensions are being registered.
     */
    public static final class Extensions implements IClientItemExtensions {
        private EntangledKnotRenderer renderer;

        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if (renderer == null) {
                Minecraft mc = Minecraft.getInstance();
                renderer = new EntangledKnotRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
            }
            return renderer;
        }
    }
}
