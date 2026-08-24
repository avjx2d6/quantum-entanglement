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
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

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

    /** How far the focus end falls once the machine lets go of it, in blocks. */
    private static final float FAIL_DROP = 1.15f;
    /** Extra droop at the middle of a slack strand, in blocks. */
    private static final float FAIL_SAG = 0.55f;

    /** Deflection of one walk step, in blocks, before amplitude is applied. */
    private static final float WALK_UNIT = 0.085f;
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
            case SCANNING, JUDGEMENT, CRESCENDO, FAILURE -> 4;
            default -> 0;
        };
    }

    /** 0 while the ritual is alive, 0→1 across the failure phase. */
    private static float failure(QuantumCoreBlockEntity.Phase phase, int age, float partialTick) {
        return phase == QuantumCoreBlockEntity.Phase.FAILURE
                ? Mth.clamp((age + partialTick) / QuantumCoreBlockEntity.FAILURE_TICKS, 0, 1)
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
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
        // a failing beam enters at the crescendo's agitation and settles out of it
        float ampScale = fail > 0 ? 1.0f + 1.8f * (1 - fail) : 1.0f + ramp * 1.8f;
        int recoloredCount = recolored(phase, age);

        if (BeamTuning.style == BeamTuning.Style.PARTICLES) {
            spawnDust(core, lit, recoloredCount, ramp);
            return false;
        }

        int n = Mth.clamp(BeamTuning.nodes, 3, 64);
        WalkState walk = advanceWalk(core, n);
        Vec3 focus = Vec3.atCenterOf(core.getBlockPos()).add(0, FOCUS_HEIGHT - 0.5, 0);
        // On a failure the machine lets go of the focus first: that end drops
        // under gravity while the resonators keep holding theirs, so the four
        // strands are left hanging rather than simply switched off.
        if (fail > 0) {
            focus = focus.subtract(0, FAIL_DROP * fail * fail, 0);
        }

        float radius = Math.max(0.005f, BeamTuning.width) * (1.0f + ramp * 0.6f) * 0.5f;
        var consumer = buffer.getBuffer(QuantumRenderTypes.RITUAL_BEAM);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (int b = 0; b < lit; b++) {
            Vec3 from = Vec3.atBottomCenterOf(core.getBlockPos().offset(CORNERS[b])).add(0, RESONATOR_TOP, 0);
            Vec3[] pts = buildBeam(walk, b, n, from, focus, partialTick, ampScale, fail);
            float[] bright = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float w = Mth.lerp(partialTick, walk.prev[b][i][2], walk.cur[b][i][2]);
                bright[i] = Mth.clamp(0.8f + w * 0.45f, 0.55f, 1.0f) * BeamTuning.brightness;
                if (fail > 0) {
                    // the light drains out of the focus end and runs back along
                    // the strand toward the resonator, which goes dark last
                    float t = i / (float) n;
                    bright[i] *= 1 - Mth.clamp((fail - (1 - t) * 0.55f) / 0.45f, 0, 1);
                }
            }
            StrandGeometry.tube(poseStack, consumer, pts, bright,
                    colorFor(b, core, recoloredCount), radius, SIDES, false);
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
            st.step(random, BeamTuning.decay, BeamTuning.spread);
        }
        st.lastTick = now;
        return st;
    }

    /**
     * Node positions in world space, offset in the plane across the beam, plus
     * the sag a slack strand takes on once the ritual has failed. The sag is a
     * parabola pinned at both ends, so the resonator's end stays put and the
     * fall reads as the middle giving way rather than the whole line sliding.
     */
    private static Vec3[] buildBeam(WalkState walk, int beam, int n, Vec3 from, Vec3 to,
                                    float blend, float ampScale, float fail) {
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
            float k = WALK_UNIT * BeamTuning.amplitude * ampScale;
            double ox = Mth.lerp(blend, walk.prev[beam][i][0], walk.cur[beam][i][0]) * k;
            double oy = Mth.lerp(blend, walk.prev[beam][i][1], walk.cur[beam][i][1]) * k;
            double sag = fail > 0 ? FAIL_SAG * fail * fail * 4 * t * (1 - t) : 0;
            out[i] = from.add(to.subtract(from).scale(t)).add(u.scale(ox)).add(v.scale(oy))
                    .subtract(0, sag, 0);
        }
        return out;
    }

    // ---- the old look, kept so it can be compared against in the same world ----

    private static void spawnDust(QuantumCoreBlockEntity core, int lit, int recoloredCount, float ramp) {
        var level = core.getLevel();
        if (level == null) {
            return;
        }
        Vec3 focus = Vec3.atCenterOf(core.getBlockPos()).add(0, 0.95, 0);
        int density = ramp > 0 ? 4 : 2;
        for (int i = 0; i < lit; i++) {
            Vec3 from = Vec3.atBottomCenterOf(core.getBlockPos().offset(CORNERS[i])).add(0, RESONATOR_TOP, 0);
            int rgb = colorFor(i, core, recoloredCount);
            Vector3f color = new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
            for (int s = 0; s < density; s++) {
                Vec3 point = from.lerp(focus, level.random.nextDouble());
                level.addParticle(new DustParticleOptions(color, 1.0f), point.x, point.y, point.z, 0, 0, 0);
            }
        }
    }
}
