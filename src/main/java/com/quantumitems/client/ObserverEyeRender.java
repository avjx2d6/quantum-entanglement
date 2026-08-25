package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

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

    private ObserverEyeRender() {
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
