package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quantumitems.block.QuantumCoreBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * The ritual beams, drawn on the client from state the core already syncs.
 *
 * <p>The beams used to be sprayed server-side as coloured dust: a packet per
 * burst to every tracking player, and "louder" could only mean "spray more".
 * Here the whole script — which beams are lit, which recoloured, which one goes
 * gold, and how hard the crescendo pushes — is derived from {@code phase} and
 * {@code phaseAge}, which the block entity already sends. No packets, and the
 * intensity can follow a curve instead of a particle count.
 *
 * <p>Both endpoints are fixed for the life of the circle, so every beam is
 * exactly the same length (2.83 blocks). That kills the need for the adaptive
 * node-count maths a general-purpose beam needs: the node count is just a
 * constant.
 */
public final class RitualBeamRenderer {

    private static final BlockPos[] CORNERS = {
            new BlockPos(-2, 0, -2), new BlockPos(2, 0, -2),
            new BlockPos(-2, 0, 2), new BlockPos(2, 0, 2)};

    private static final Vector3f COLOR_CHARGE = new Vector3f(0.75f, 0.55f, 1.0f);
    private static final Vector3f COLOR_INPUT = new Vector3f(0.35f, 0.9f, 1.0f);
    private static final Vector3f COLOR_OUTPUT = new Vector3f(1.0f, 0.84f, 0.3f);

    /** Deflection of one walk step, in blocks, before amplitude is applied. */
    private static final float WALK_UNIT = 0.085f;

    private RitualBeamRenderer() {
    }

    // ---- per-core random-walk state, advanced lazily from the game clock ----

    private static final Map<BlockPos, WalkState> WALKS = new HashMap<>();

    private static final class WalkState {
        long lastTick = Long.MIN_VALUE;
        int nodes = -1;
        /** [beam][node][axis] offsets, plus the previous tick for interpolation. */
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

    /** Drops state for cores that are gone; called when a core stops running. */
    public static void forget(BlockPos pos) {
        WALKS.remove(pos);
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

    public static void render(QuantumCoreBlockEntity core, float partialTick, PoseStack poseStack,
                              MultiBufferSource buffers) {
        QuantumCoreBlockEntity.Phase phase = core.phase();
        int age = core.phaseAge();
        int lit = connectedBeams(phase, age);
        if (lit <= 0 || core.getLevel() == null) {
            return;
        }
        // crescendo pushes amplitude and speed along one curve, the same shape
        // the riser sound climbs on
        float ramp = phase == QuantumCoreBlockEntity.Phase.CRESCENDO
                ? Mth.clamp((age + partialTick) / QuantumCoreBlockEntity.CRESCENDO_TICKS, 0, 1) : 0;
        float ampScale = 1.0f + ramp * 1.8f;
        float intensity = 0.75f + ramp * 0.35f;

        if (BeamTuning.style == BeamTuning.Style.PARTICLES) {
            spawnDust(core, lit, recolored(phase, age), ramp);
            return;
        }

        int n = Mth.clamp(BeamTuning.nodes, 3, 64);
        WalkState walk = advanceWalk(core, n);
        float time = (core.getLevel().getGameTime() % 100000L) + partialTick;
        float walkBlend = partialTick;

        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 camLocal = cam.subtract(Vec3.atLowerCornerOf(core.getBlockPos()));
        VertexConsumer buffer = buffers.getBuffer(QuantumRenderTypes.RITUAL_BEAM);

        Vec3 focus = new Vec3(0.5, 1.45, 0.5); // the shard, in block-local space
        for (int b = 0; b < lit; b++) {
            Vec3 from = new Vec3(CORNERS[b].getX() + 0.5, 1.3, CORNERS[b].getZ() + 0.5);
            Vector3f color = colorFor(b, phase, recolored(phase, age));
            Vec3[] pts = buildBeam(walk, b, n, from, focus, walkBlend, ampScale);
            draw(poseStack, buffer, pts, camLocal, color, intensity);
        }
    }

    private static Vector3f colorFor(int beam, QuantumCoreBlockEntity.Phase phase, int recoloredCount) {
        boolean verdict = phase == QuantumCoreBlockEntity.Phase.JUDGEMENT
                || phase == QuantumCoreBlockEntity.Phase.CRESCENDO;
        if (verdict && beam == 3) {
            return COLOR_OUTPUT;
        }
        return beam < recoloredCount ? COLOR_INPUT : COLOR_CHARGE;
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

    /** Node positions in block-local space, offset in the plane across the beam. */
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
            double ox = 0, oy = 0;
            {
                float k = WALK_UNIT * BeamTuning.amplitude * ampScale;
                ox += Mth.lerp(blend, walk.prev[beam][i][0], walk.cur[beam][i][0]) * k;
                oy += Mth.lerp(blend, walk.prev[beam][i][1], walk.cur[beam][i][1]) * k;
            }
            Vec3 base = from.add(to.subtract(from).scale(t));
            out[i] = base.add(u.scale(ox)).add(v.scale(oy));
        }
        return out;
    }

    // ---- drawing: camera-facing quads per segment, additive ----

    private static void draw(PoseStack poseStack, VertexConsumer buffer, Vec3[] pts, Vec3 cam,
                             Vector3f color, float intensity) {
        float w = Math.max(0.005f, BeamTuning.width);
        if (BeamTuning.glow) {
            ribbon(poseStack, buffer, pts, cam, color, w * 4.0f, 0.16f * intensity);
        }
        ribbon(poseStack, buffer, pts, cam, color, w * 1.6f, 0.55f * intensity);
        ribbon(poseStack, buffer, pts, cam, new Vector3f(1, 1, 1), w * 0.7f, 0.85f * intensity);
    }

    /**
     * One camera-facing strip per segment, drawn as two quads that meet on the
     * centre line: full alpha along the middle, zero at both outer edges.
     *
     * <p>A strip of constant alpha is what makes a billboard read as a pane of
     * glass — its edge is a hard line at a fixed width, and the eye reads a flat
     * sheet. Fading to nothing at the edges leaves no silhouette to read, so the
     * same geometry reads as a glow around a filament instead.
     */
    private static void ribbon(PoseStack poseStack, VertexConsumer buffer, Vec3[] pts, Vec3 cam,
                               Vector3f color, float halfWidth, float alpha) {
        var pose = poseStack.last().pose();
        int r = (int) (color.x() * 255), g = (int) (color.y() * 255), b = (int) (color.z() * 255);
        int a = (int) (Mth.clamp(alpha, 0, 1) * 255);
        for (int i = 0; i < pts.length - 1; i++) {
            Vec3 p0 = pts[i], p1 = pts[i + 1];
            Vec3 seg = p1.subtract(p0);
            if (seg.lengthSqr() < 1.0e-9) {
                continue;
            }
            Vec3 toCam = cam.subtract(p0.add(p1).scale(0.5));
            Vec3 side = seg.cross(toCam);
            if (side.lengthSqr() < 1.0e-9) {
                continue;
            }
            side = side.normalize().scale(halfWidth);
            Vec3 l0 = p0.subtract(side), l1 = p1.subtract(side);
            Vec3 r0 = p0.add(side), r1 = p1.add(side);
            // left edge -> centre
            vertex(buffer, pose, l0, r, g, b, 0);
            vertex(buffer, pose, p0, r, g, b, a);
            vertex(buffer, pose, p1, r, g, b, a);
            vertex(buffer, pose, l1, r, g, b, 0);
            // centre -> right edge
            vertex(buffer, pose, p0, r, g, b, a);
            vertex(buffer, pose, r0, r, g, b, 0);
            vertex(buffer, pose, r1, r, g, b, 0);
            vertex(buffer, pose, p1, r, g, b, a);
        }
    }

    private static void vertex(VertexConsumer buffer, org.joml.Matrix4f pose, Vec3 p,
                               int r, int g, int b, int alpha) {
        buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, alpha);
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
            Vec3 from = Vec3.atCenterOf(core.getBlockPos().offset(CORNERS[i])).add(0, 0.8, 0);
            Vector3f color = colorFor(i, core.phase(), recoloredCount);
            for (int s = 0; s < density; s++) {
                Vec3 point = from.lerp(focus, level.random.nextDouble());
                level.addParticle(new DustParticleOptions(color, 1.0f), point.x, point.y, point.z, 0, 0, 0);
            }
        }
    }
}
