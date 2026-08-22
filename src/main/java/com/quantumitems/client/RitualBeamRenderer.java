package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.block.QuantumCoreBlockEntity;
import com.quantumitems.engine.ActiveRitualCores;
import net.createmod.catnip.outliner.LineOutline;
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
 * <p>The line itself is Catnip's {@link LineOutline}: a real cuboid segment,
 * not a camera-facing strip. Billboards are what made the first attempt read as
 * panes of glass — each segment is a flat quad, and neighbouring segments that
 * move independently visibly overlap.
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

    private static final int COLOR_CHARGE = 0xBF8CFF;
    private static final int COLOR_INPUT = 0x59E6FF;
    private static final int COLOR_OUTPUT = 0xFFD64D;

    /** Deflection of one walk step, in blocks, before amplitude is applied. */
    private static final float WALK_UNIT = 0.085f;
    /** Top face of the resonator model — the beam lands on it, not above it. */
    private static final double RESONATOR_TOP = 1.0;
    /** The shard inside the upper frame. */
    private static final double FOCUS_HEIGHT = 1.45;

    private static final LineOutline LINE = new LineOutline();

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
            cur = new float[4][n + 1][2];
            prev = new float[4][n + 1][2];
        }

        void step(RandomSource random, float decay, float spread) {
            for (int b = 0; b < 4; b++) {
                for (int i = 0; i <= nodes; i++) {
                    prev[b][i][0] = cur[b][i][0];
                    prev[b][i][1] = cur[b][i][1];
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
            case SCANNING, JUDGEMENT, CRESCENDO -> 4;
            default -> 0;
        };
    }

    private static int recolored(QuantumCoreBlockEntity.Phase phase, int age) {
        return switch (phase) {
            case SCANNING -> 1 + age / QuantumCoreBlockEntity.BEAM_STEP_TICKS;
            case JUDGEMENT, CRESCENDO -> 4;
            default -> 0;
        };
    }

    private static int colorFor(int beam, QuantumCoreBlockEntity.Phase phase, int recoloredCount) {
        boolean verdict = phase == QuantumCoreBlockEntity.Phase.JUDGEMENT
                || phase == QuantumCoreBlockEntity.Phase.CRESCENDO;
        if (verdict && beam == 3) {
            return COLOR_OUTPUT;
        }
        return beam < recoloredCount ? COLOR_INPUT : COLOR_CHARGE;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || ActiveRitualCores.positions(level).isEmpty()) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        boolean drewAnything = false;

        for (BlockPos pos : ActiveRitualCores.positions(level)) {
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
        float ampScale = 1.0f + ramp * 1.8f;
        int recoloredCount = recolored(phase, age);

        if (BeamTuning.style == BeamTuning.Style.PARTICLES) {
            spawnDust(core, lit, recoloredCount, ramp);
            return false;
        }

        int n = Mth.clamp(BeamTuning.nodes, 3, 64);
        WalkState walk = advanceWalk(core, n);
        Vec3 focus = Vec3.atCenterOf(core.getBlockPos()).add(0, FOCUS_HEIGHT - 0.5, 0);

        LINE.getParams()
                .disableLineNormals()
                .disableCull()
                // full-bright: the beam emits, it is not a surface being lit.
                // Without this it takes the world's light and reads as flat
                // paint after dark instead of something glowing.
                .lightmap(0xF000F0)
                .lineWidth(Math.max(0.005f, BeamTuning.width) * (1.0f + ramp * 0.6f));

        for (int b = 0; b < lit; b++) {
            Vec3 from = Vec3.atBottomCenterOf(core.getBlockPos().offset(CORNERS[b])).add(0, RESONATOR_TOP, 0);
            LINE.getParams().colored(colorFor(b, phase, recoloredCount));
            Vec3[] pts = buildBeam(walk, b, n, from, focus, partialTick, ampScale);
            for (int i = 0; i < pts.length - 1; i++) {
                LINE.set(pts[i], pts[i + 1]).render(poseStack, buffer, camera, partialTick);
            }
        }
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
            float k = WALK_UNIT * BeamTuning.amplitude * ampScale;
            double ox = Mth.lerp(blend, walk.prev[beam][i][0], walk.cur[beam][i][0]) * k;
            double oy = Mth.lerp(blend, walk.prev[beam][i][1], walk.cur[beam][i][1]) * k;
            out[i] = from.add(to.subtract(from).scale(t)).add(u.scale(ox)).add(v.scale(oy));
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
            int rgb = colorFor(i, core.phase(), recoloredCount);
            Vector3f color = new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
            for (int s = 0; s < density; s++) {
                Vec3 point = from.lerp(focus, level.random.nextDouble());
                level.addParticle(new DustParticleOptions(color, 1.0f), point.x, point.y, point.z, 0, 0, 0);
            }
        }
    }
}
