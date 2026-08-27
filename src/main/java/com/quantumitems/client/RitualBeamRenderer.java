package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.block.QuantumCoreBlockEntity;
import com.quantumitems.engine.ActiveRitualCores;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The ritual beams, drawn on the client from state the core already syncs.
 *
 * <p>They used to be sprayed server-side as coloured dust: a packet per burst
 * to every tracking player, and "louder" could only mean "spray more". The
 * whole script — which beams are lit, which recoloured, which one goes gold,
 * how hard the crescendo pushes — follows from {@code phase} and
 * {@code phaseAge}, which the block entity already sends, so none of it needs
 * the network and the intensity can ride a curve instead of a particle count.
 *
 * <p>Drawn from the level render event rather than the core's block-entity
 * renderer. A BER only runs while its own block is on screen, which made every
 * beam in the circle vanish the moment the core left the edge of view.
 *
 * <p>The line itself is a tube of shared rings — see {@link StrandGeometry},
 * which the shard's knot draws with too.
 *
 * <p>Both endpoints are fixed for the life of a circle, so every beam is
 * exactly the same length. That removes the adaptive node-count maths a
 * general-purpose beam needs; the node count is simply a constant.
 */
@EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
public final class RitualBeamRenderer {

    private static final BlockPos[] CORNERS = {
            new BlockPos(-2, 0, -2), new BlockPos(2, 0, -2),
            new BlockPos(-2, 0, 2), new BlockPos(2, 0, 2)};

    // Deeper than the pastels the dust used: additive blending already lifts a
    // colour toward white, so the hue has to start saturated and low-value or
    // the beam ends up as glare with no colour left in it.
    private static final int COLOR_CHARGE = 0x8243E0;   // deep violet
    private static final int COLOR_INPUT = 0x21BED9;    // deep cyan
    private static final int COLOR_OUTPUT = 0xE0B01B;   // deep amber
    private static final int COLOR_FAIL = 0xE02718;     // deep red
    private static final int COLOR_WON = 0x1FD99A;      // deep green-turquoise

    /** Blocks a fragment falls by the end of the failure phase. */
    private static final float FAIL_GRAVITY = 2.6f;
    /** Degrees a fragment tumbles through while it falls. */
    private static final float FAIL_TUMBLE = 220.0f;
    /** Points per fragment; neighbours share an endpoint, so the line starts whole. */
    private static final int FRAGMENT_POINTS = 3;

    /** The discharge ring leaves the circle entirely — the resonators sit at 2.83. */
    private static final float RING_MAX = 14.0f;
    /** Enough that a fourteen-block circle does not read as a polygon. */
    private static final int RING_NODES = 64;

    // The look, settled in-world with /qbeam and then frozen. That command and
    // the BeamTuning class it drove are gone; these are the winning numbers.
    /** Deflection of one walk step, in blocks. */
    private static final float WALK_UNIT = 0.085f;
    /** Overall deflection from the straight line. */
    private static final float AMPLITUDE = 0.9f;
    /** Segments per beam. Every beam is 2.83 blocks, so this is simply fixed. */
    private static final int NODES = 12;
    /** Random walk pull-back per tick. Higher drifts slower AND wider. */
    private static final float DECAY = 0.8f;
    /** Random walk kick per tick. */
    private static final float SPREAD = 0.7f;
    /**
     * How hard a beam emits. Depth is tested but not written, so the far wall
     * of the tube shows through the near one and every pixel gets its colour
     * added twice — which is what sets the ceiling here, not taste.
     */
    private static final float BRIGHTNESS = 0.5f;
    /** Line width in blocks. Wider than this and the joints start showing. */
    private static final float WIDTH = 0.6f / 16f;
    /** Top face of the resonator model — the beam lands on it, not above it. */
    private static final double RESONATOR_TOP = 1.0;
    /** The shard inside the upper frame. */
    private static final double FOCUS_HEIGHT = 1.45;

    /** Faces around the tube. Four reads chunky, eight is round; six sits between. */
    private static final int SIDES = 6;

    private RitualBeamRenderer() {
    }

    // ---- per-core random-walk state, advanced lazily from the game clock ----

    private static final Map<BlockPos, WalkState> WALKS = new HashMap<>();

    private static final class WalkState {
        long lastTick = Long.MIN_VALUE;
        int nodes = -1;
        float[][][] cur, prev;

        void resize(int n) {
            nodes = n;
            cur = new float[4][n + 1][3];   // x, y across the beam; z = brightness
            prev = new float[4][n + 1][3];
        }

        void step(RandomSource random, float decay, float spread) {
            for (int b = 0; b < 4; b++) {
                for (int i = 0; i <= nodes; i++) {
                    prev[b][i][0] = cur[b][i][0];
                    prev[b][i][1] = cur[b][i][1];
                    prev[b][i][2] = cur[b][i][2];
                    // brightness wanders on its own everywhere, including the
                    // pinned ends — vanilla dust varies every mote, and a beam
                    // of one flat colour is what reads as painted plastic
                    cur[b][i][2] = (cur[b][i][2] + (random.nextFloat() - 0.5f)) * 0.75f;
                    if (i == 0 || i == nodes) {
                        cur[b][i][0] = 0;
                        cur[b][i][1] = 0;
                        continue;
                    }
                    cur[b][i][0] = (cur[b][i][0] + (random.nextFloat() - 0.5f) * spread) * decay;
                    cur[b][i][1] = (cur[b][i][1] + (random.nextFloat() - 0.5f) * spread) * decay;
                }
            }
        }
    }

    public static void forgetAll() {
        WALKS.clear();
    }

    // ---- the script, read straight off the synced phase ----

    private static int connectedBeams(QuantumCoreBlockEntity.Phase phase, int age) {
        return switch (phase) {
            case CONNECTING -> Math.min(4, 1 + age / QuantumCoreBlockEntity.BEAM_STEP_TICKS);
            case SCANNING, JUDGEMENT, CRESCENDO, FAILURE, SUCCESS -> 4;
            default -> 0;
        };
    }

    /** 0 while the ritual is alive, 0→1 across the failure phase. */
    private static float failure(QuantumCoreBlockEntity.Phase phase, int age, float partialTick) {
        return phase == QuantumCoreBlockEntity.Phase.FAILURE
                ? Mth.clamp((age + partialTick) / QuantumCoreBlockEntity.FAILURE_TICKS, 0, 1)
                : 0;
    }

    /** 0 while the ritual is alive, 0→1 across the success phase. */
    private static float success(QuantumCoreBlockEntity.Phase phase, int age, float partialTick) {
        return phase == QuantumCoreBlockEntity.Phase.SUCCESS
                ? Mth.clamp((age + partialTick) / QuantumCoreBlockEntity.SUCCESS_TICKS, 0, 1)
                : 0;
    }

    private static int recolored(QuantumCoreBlockEntity.Phase phase, int age) {
        return switch (phase) {
            case SCANNING -> 1 + age / QuantumCoreBlockEntity.BEAM_STEP_TICKS;
            case JUDGEMENT, CRESCENDO -> 4;
            default -> 0;
        };
    }

    /** Is that corner's resonator holding something? Only laid stacks are inputs. */
    private static boolean occupied(QuantumCoreBlockEntity core, int corner) {
        return core.getLevel() != null
                && core.getLevel().getBlockEntity(core.getBlockPos().offset(CORNERS[corner]))
                        instanceof com.quantumitems.block.ResonatorBlockEntity resonator
                && !resonator.isEmpty();
    }

    /**
     * Gold marks the corner the ritual actually picked for its output, which
     * the core syncs — pointing it anywhere else tells the player the result
     * will land somewhere it will not. Inputs go cyan in the order they are
     * met, and only occupied resonators are inputs at all.
     */
    private static int colorFor(int beam, QuantumCoreBlockEntity core, int recoloredCount) {
        QuantumCoreBlockEntity.Phase phase = core.phase();
        if (phase == QuantumCoreBlockEntity.Phase.FAILURE) {
            return COLOR_FAIL;
        }
        if (phase == QuantumCoreBlockEntity.Phase.SUCCESS) {
            return COLOR_WON;
        }
        boolean verdict = phase == QuantumCoreBlockEntity.Phase.JUDGEMENT
                || phase == QuantumCoreBlockEntity.Phase.CRESCENDO;
        if (verdict && beam == core.plannedOutputCorner()) {
            return COLOR_OUTPUT;
        }
        if (!occupied(core, beam)) {
            return COLOR_CHARGE;
        }
        int seen = 0;
        for (int i = 0; i <= beam; i++) {
            if (occupied(core, i)) {
                seen++;
            }
        }
        return seen <= recoloredCount ? COLOR_INPUT : COLOR_CHARGE;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!QuantumRenderTypes.isStrandStage(event.getStage())) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || ActiveRitualCores.beingDrawn(level).isEmpty()) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        boolean drewAnything = false;

        for (BlockPos pos : ActiveRitualCores.beingDrawn(level)) {
            if (!(level.getBlockEntity(pos) instanceof QuantumCoreBlockEntity core)) {
                continue;
            }
            drewAnything |= renderCore(core, partialTick, poseStack, buffer, camera);
        }
        if (drewAnything) {
            buffer.draw();
        }
    }

    private static boolean renderCore(QuantumCoreBlockEntity core, float partialTick, PoseStack poseStack,
                                      SuperRenderTypeBuffer buffer, Vec3 camera) {
        QuantumCoreBlockEntity.Phase phase = core.phase();
        int age = core.phaseAge();
        int lit = connectedBeams(phase, age);
        if (lit <= 0 || core.getLevel() == null) {
            return false;
        }
        // the crescendo pushes amplitude along the same curve the riser climbs
        float ramp = phase == QuantumCoreBlockEntity.Phase.CRESCENDO
                ? Mth.clamp((age + partialTick) / QuantumCoreBlockEntity.CRESCENDO_TICKS, 0, 1) : 0;
        float fail = failure(phase, age, partialTick);
        float won = success(phase, age, partialTick);
        // A failing beam enters at the crescendo's agitation and settles out of
        // it; a succeeding one is snapped, and whips harder than the crescendo
        // ever pushed before it goes.
        float ampScale = 1.0f + ramp * 1.8f;
        if (fail > 0) {
            ampScale = 1.0f + 1.8f * (1 - fail);
        } else if (won > 0) {
            ampScale = 1.0f + 4.5f * (1 - won) * (1 - won);
        }
        int recoloredCount = recolored(phase, age);

        int n = NODES;
        WalkState walk = advanceWalk(core, n);
        Vec3 focus = Vec3.atCenterOf(core.getBlockPos()).add(0, FOCUS_HEIGHT - 0.5, 0);

        float radius = WIDTH * (1.0f + ramp * 0.6f) * 0.5f;
        var consumer = buffer.getBuffer(QuantumRenderTypes.RITUAL_BEAM);
        ShaderWarning.checkOnce();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        if (won > 0) {
            shockRing(poseStack, consumer, focus, won);
        }
        for (int b = 0; b < lit; b++) {
            Vec3 from = Vec3.atBottomCenterOf(core.getBlockPos().offset(CORNERS[b])).add(0, RESONATOR_TOP, 0);
            Vec3 to = focus;
            if (won > 0) {
                // The entanglement is made and the strand is paid back out into
                // the resonator that fed it: the focus end travels outward, so
                // the line shortens from the middle rather than fading in place.
                to = focus.add(from.subtract(focus).scale(Mth.clamp(Math.pow(won, 0.6), 0, 0.995)));
            }
            Vec3[] pts = buildBeam(walk, b, n, from, to, partialTick, ampScale);
            float[] bright = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float w = Mth.lerp(partialTick, walk.prev[b][i][2], walk.cur[b][i][2]);
                bright[i] = Mth.clamp(0.8f + w * 0.45f, 0.55f, 1.0f) * BRIGHTNESS;
                if (won > 0) {
                    // A spike steep enough to be a flash rather than a glare —
                    // (1-t)^4 has spent itself in three ticks — over a fade slow
                    // enough that the strand is still visibly moving when the
                    // ring reaches the resonators.
                    float left = 1 - won;
                    bright[i] *= (1 + left * left * left * left) * (float) Math.pow(left, 1.2);
                }
            }
            int rgb = colorFor(b, core, recoloredCount);
            if (fail > 0) {
                brokenStrand(poseStack, consumer, pts, bright, rgb, radius, b, fail);
            } else {
                StrandGeometry.tube(poseStack, consumer, pts, bright, rgb, radius, SIDES, false);
            }
        }
        poseStack.popPose();
        return true;
    }

    private static WalkState advanceWalk(QuantumCoreBlockEntity core, int n) {
        WalkState st = WALKS.computeIfAbsent(core.getBlockPos().immutable(), p -> new WalkState());
        if (st.nodes != n) {
            st.resize(n);
        }
        long now = core.getLevel().getGameTime();
        if (st.lastTick == Long.MIN_VALUE) {
            st.lastTick = now - 1;
        }
        long steps = Math.min(4, now - st.lastTick); // a lag spike must not spin the walk
        RandomSource random = core.getLevel().getRandom();
        for (long i = 0; i < steps; i++) {
            st.step(random, DECAY, SPREAD);
        }
        st.lastTick = now;
        return st;
    }

    /**
     * The discharge: a ring of the same strand thrown out flat from the focus,
     * sweeping out over the four resonators and gone.
     *
     * <p>It replaces a single {@code FLASH} particle. A flash is a sprite that
     * happens at a point; the circle of resonators is what the ritual is, and a
     * ring leaving through them says the circle has just spent itself. The tube
     * closes on itself here — see {@link StrandGeometry}'s loop handling, which
     * the shard's knot needs for the same reason.
     */
    private static void shockRing(PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                  Vec3 focus, float won) {
        // out fast, then slowing — an impulse spending itself against the air
        float spread = 1 - (1 - won) * (1 - won);
        double radius = Mth.lerp(spread, 0.25f, RING_MAX);
        float fade = (float) Math.pow(1 - won, 1.3);
        Vec3[] pts = new Vec3[RING_NODES + 1];
        float[] bright = new float[RING_NODES + 1];
        for (int i = 0; i < RING_NODES; i++) {
            double a = 2 * Math.PI * i / RING_NODES;
            // a little out of round, so it reads as a discharge and not as a hoop
            double wobble = radius * 0.06 * Math.sin(a * 5 + won * 9);
            pts[i] = focus.add((radius + wobble) * Math.cos(a), 0, (radius + wobble) * Math.sin(a));
            bright[i] = Mth.clamp(fade * (0.85f + 0.15f * (float) Math.sin(a * 3)), 0, 1)
                    * BRIGHTNESS * 1.3f;
        }
        pts[RING_NODES] = pts[0];
        bright[RING_NODES] = bright[0];
        // the ring thins as it grows: the same light spread around a longer line
        float radiusOut = WIDTH * 0.5f * (1.4f - 0.9f * spread);
        StrandGeometry.tube(poseStack, consumer, pts, bright, COLOR_WON, radiusOut, SIDES, true);
    }

    /**
     * The strand comes apart. Rather than one line going slack, it is drawn as
     * short pieces that each fall and tumble on their own — which is what a
     * thing under tension does when it lets go, and what a single sagging line
     * could never show.
     *
     * <p>Neighbouring pieces share an endpoint, so at the instant of failure the
     * line is still whole and only comes apart as they separate. Everything a
     * piece needs — when it lets go, which way it tumbles — is derived from its
     * beam and its index, never drawn from a random source: it has to be the
     * same value on the next frame or the pieces would jitter in place.
     *
     * <p>They break from the focus outward, so the middle of the circle gives
     * way first and the resonators are left holding the last of it.
     */
    private static void brokenStrand(PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                     Vec3[] pts, float[] bright, int rgb, float radius,
                                     int beam, float fail) {
        int last = pts.length - 1;
        for (int start = 0; start < last; start += FRAGMENT_POINTS - 1) {
            int end = Math.min(start + FRAGMENT_POINTS - 1, last);
            if (end - start < 1) {
                break;
            }
            // 1 at the focus end, 0 at the resonator: the focus lets go first
            float atFocus = (start + end) * 0.5f / last;
            float scatter = hash01(beam * 31 + start);
            float held = 0.30f * (1 - atFocus) + 0.10f * scatter;
            float g = Math.max(0, (fail - held) / Math.max(1.0e-3f, 1 - held));
            if (g <= 0) {
                StrandGeometry.tube(poseStack, consumer, slice(pts, start, end),
                        slice(bright, start, end), rgb, radius, SIDES, false);
                continue;
            }

            Vec3[] piece = slice(pts, start, end);
            Vec3 centre = Vec3.ZERO;
            for (Vec3 p : piece) {
                centre = centre.add(p);
            }
            centre = centre.scale(1.0 / piece.length);
            // a fixed tumble axis per piece, spread around the circle by its hash
            double spin = Math.toRadians(FAIL_TUMBLE * g * (scatter < 0.5f ? -1 : 1));
            Vec3 axis = new Vec3(Math.cos(scatter * 6.283), 0.35, Math.sin(scatter * 6.283)).normalize();
            double drop = FAIL_GRAVITY * g * g;
            double drift = 0.35 * g * (scatter - 0.5);
            for (int i = 0; i < piece.length; i++) {
                Vec3 local = piece[i].subtract(centre);
                // Rodrigues about the piece's own axis
                Vec3 turned = local.scale(Math.cos(spin))
                        .add(axis.cross(local).scale(Math.sin(spin)))
                        .add(axis.scale(axis.dot(local) * (1 - Math.cos(spin))));
                piece[i] = centre.add(turned).add(drift, -drop, drift);
            }
            float[] fade = slice(bright, start, end);
            float left = 1 - g;
            for (int i = 0; i < fade.length; i++) {
                fade[i] *= left * left;
            }
            StrandGeometry.tube(poseStack, consumer, piece, fade, rgb, radius, SIDES, false);
        }
    }

    private static Vec3[] slice(Vec3[] src, int from, int to) {
        Vec3[] out = new Vec3[to - from + 1];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static float[] slice(float[] src, int from, int to) {
        float[] out = new float[to - from + 1];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    /** Stable pseudo-random in 0..1 from an integer — the same every frame. */
    private static float hash01(int seed) {
        int h = seed * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h >>> 8) / (float) (1 << 24);
    }

    /** Node positions in world space, offset in the plane across the beam. */
    private static Vec3[] buildBeam(WalkState walk, int beam, int n, Vec3 from, Vec3 to,
                                    float blend, float ampScale) {
        Vec3 dir = to.subtract(from).normalize();
        Vec3 u = dir.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0e-6) {
            u = dir.cross(new Vec3(1, 0, 0));
        }
        u = u.normalize();
        Vec3 v = dir.cross(u).normalize();

        Vec3[] out = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float k = WALK_UNIT * AMPLITUDE * ampScale;
            double ox = Mth.lerp(blend, walk.prev[beam][i][0], walk.cur[beam][i][0]) * k;
            double oy = Mth.lerp(blend, walk.prev[beam][i][1], walk.cur[beam][i][1]) * k;
            out[i] = from.add(to.subtract(from).scale(t)).add(u.scale(ox)).add(v.scale(oy));
        }
        return out;
    }
}
