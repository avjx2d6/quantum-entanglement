package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

/**
 * The Observer: a small gem-cube stood on one corner, drawn with manual quads.
 *
 * <p>It lives in code rather than in the block models for a reason that is not
 * a matter of preference. Standing a cube on its vertex is a turn of 45° about
 * Y and then 54.7356° — atan(√2) — about Z, and a Java block element takes one
 * axis and one of five angles. Blockbench will happily export the two-axis
 * rotation, and {@code BlockElement.Deserializer} then throws on the missing
 * "axis" key, which fails the WHOLE model rather than that one cube. So the
 * housing is a model and the gem inside it is not.
 *
 * <p>The geometry and the per-face UVs below are the author's cube out of
 * quantum_core_lower_e.bbmodel, transcribed: a 4-pixel cube whose six faces are
 * unwrapped across one 16×16 sprite, two of them mirrored.
 */
public final class ObserverEyeRender {
    private static final ResourceLocation EYE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("quantumitems", "block/observer_eye");

    /** Edge of the gem in blocks — 4 pixels, as modelled. */
    public static final float SIZE = 4 / 16f;
    /** Y of the gem's centre inside its housing, from the block's floor. The
     *  core and the resonator model it at the same height. */
    public static final float CORE_HEIGHT = 8.5f / 16f;
    /** The two turns that stand a cube on a vertex, in the order Blockbench applies them. */
    public static final float GEM_YAW = 45.0f;
    public static final float GEM_TIP = 54.7356f;

    // ---- the corona ----

    /** Deep teal, the eye's own colour rather than any of the beams'. */
    private static final int CORONA_COLOR = 0x18C4A6;
    private static final int ARCS = 4;
    private static final int ARC_NODES = 6;
    /** Just clear of the gem, whose circumradius on a corner is 0.217. */
    private static final float CORONA_RADIUS = 0.225f;
    private static final float CORONA_WIDTH = 0.010f;
    /** Ticks an arc takes to fade in, crawl and go. */
    private static final float ARC_LIFE = 26.0f;

    private ObserverEyeRender() {
    }

    /**
     * Short arcs crawling over an invisible sphere around the gem, so the
     * observer is doing something even when nothing is happening.
     *
     * <p>It replaces nothing — the six PORTAL motes that marked an eaten
     * experience orb went unnoticed by the author for weeks, which is a fair
     * verdict on them. The observer is the one part of the machine that is
     * always present and was always still.
     *
     * <p>Draw this in a pose that is translated to the gem's centre but NOT
     * rotated or scaled with it: the arcs orbit the gem, they do not ride it.
     *
     * @param intensity 0 for an idle observer, 1 for one working
     */
    public static void renderCorona(PoseStack poseStack, MultiBufferSource buffers,
                                    float time, float intensity) {
        var consumer = buffers.getBuffer(QuantumRenderTypes.RITUAL_BEAM);
        int live = 1 + (int) (intensity * (ARCS - 1));
        for (int arc = 0; arc < live; arc++) {
            // Each arc has its own offset life, so they do not pulse in unison.
            float phase = time / ARC_LIFE + arc * 0.41f;
            int generation = Mth.floor(phase);
            float age = phase - generation;
            // in and out over its life; nothing pops
            float fade = Mth.sin(age * Mth.PI);
            if (fade <= 0.02f) {
                continue;
            }
            Vec3 from = onSphere(generation * 31 + arc * 7);
            Vec3 to = onSphere(generation * 31 + arc * 7 + 3);
            // Too close and there is no arc; too near antipodal and the lerp
            // passes through the origin, where normalize has no answer.
            if (Math.abs(from.dot(to)) > 0.9) {
                to = from.cross(new Vec3(0, 1, 0)).normalize();
            }
            Vec3[] pts = new Vec3[ARC_NODES + 1];
            float[] bright = new float[ARC_NODES + 1];
            for (int i = 0; i <= ARC_NODES; i++) {
                float t = i / (float) ARC_NODES;
                // slerp along the great circle, then bulge with the crawl
                Vec3 on = from.scale(1 - t).add(to.scale(t)).normalize();
                float bulge = 1 + 0.18f * Mth.sin((t * 2 + age * 3 + arc) * Mth.TWO_PI);
                pts[i] = on.scale(CORONA_RADIUS * bulge);
                // taper to nothing at both tips, so an arc has no cut ends
                bright[i] = fade * Mth.sin(t * Mth.PI) * (0.55f + 0.45f * intensity);
            }
            StrandGeometry.tube(poseStack, consumer, pts, bright, CORONA_COLOR,
                    CORONA_WIDTH, 4, false);
        }
    }

    /** A stable point on the unit sphere from an integer. */
    private static Vec3 onSphere(int seed) {
        int h = seed * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        double y = ((h >>> 8) / (double) (1 << 24)) * 2 - 1;
        double a = ((h & 0xFF) / 255.0) * Math.PI * 2;
        double r = Math.sqrt(Math.max(0, 1 - y * y));
        return new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    /** Draws a unit cube centered at the origin of the current pose; scale beforehand. */
    public static void renderEyeCube(PoseStack poseStack, MultiBufferSource buffers, int light) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(EYE_TEXTURE);
        VertexConsumer buffer = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose pose = poseStack.last();
        float h = 0.5f;
        // six faces: {normal, four corners from outside: top-left, top-right,
        // bottom-right, bottom-left} — the same order a face's uv rect is read in
        float[][][] faces = {
                {{0, 0, -1}, {h, h, -h}, {-h, h, -h}, {-h, -h, -h}, {h, -h, -h}},   // north
                {{0, 0, 1}, {-h, h, h}, {h, h, h}, {h, -h, h}, {-h, -h, h}},        // south
                {{-1, 0, 0}, {-h, h, -h}, {-h, h, h}, {-h, -h, h}, {-h, -h, -h}},   // west
                {{1, 0, 0}, {h, h, h}, {h, h, -h}, {h, -h, -h}, {h, -h, h}},        // east
                {{0, 1, 0}, {-h, h, -h}, {h, h, -h}, {h, h, h}, {-h, h, h}},        // up
                {{0, -1, 0}, {-h, -h, h}, {h, -h, h}, {h, -h, -h}, {-h, -h, -h}}    // down
        };
        // uv rects in sprite pixels, same order; up and down run backwards
        // because that is how they are unwrapped on the sheet
        float[][] rects = {
                {0, 0, 4, 4},       // north
                {4, 0, 8, 4},       // south
                {4, 4, 8, 8},       // west
                {0, 4, 4, 8},       // east
                {4, 12, 0, 8},      // up
                {12, 0, 8, 4}       // down
        };
        for (int f = 0; f < faces.length; f++) {
            float[] n = faces[f][0];
            float[] r = rects[f];
            float[][] uv = {
                    {sprite.getU(r[0] / 16f), sprite.getV(r[1] / 16f)},
                    {sprite.getU(r[2] / 16f), sprite.getV(r[1] / 16f)},
                    {sprite.getU(r[2] / 16f), sprite.getV(r[3] / 16f)},
                    {sprite.getU(r[0] / 16f), sprite.getV(r[3] / 16f)}
            };
            for (int i = 1; i <= 4; i++) {
                float[] c = faces[f][i];
                buffer.addVertex(pose, c[0], c[1], c[2])
                        .setColor(255, 255, 255, 255)
                        .setUv(uv[i - 1][0], uv[i - 1][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, n[0], n[1], n[2]);
            }
        }
    }
}
