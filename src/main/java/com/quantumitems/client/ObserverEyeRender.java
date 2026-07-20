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
 * The Observer: a small 3D gem-cube textured with the author's Eye of
 * Elsewhere sprite on every face, drawn with manual quads (no baked-model
 * registration). Rotated like a gem and spun by the caller, it reads as a
 * floating faceted eye through the wall gaps of the hollow blocks —
 * placeholder until the author's Blockbench model replaces it.
 */
public final class ObserverEyeRender {
    private static final ResourceLocation EYE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("quantumitems", "item/eye_of_elsewhere");

    private ObserverEyeRender() {
    }

    /** Draws a unit cube centered at the origin of the current pose; scale beforehand. */
    public static void renderEyeCube(PoseStack poseStack, MultiBufferSource buffers, int light) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(EYE_TEXTURE);
        VertexConsumer buffer = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose pose = poseStack.last();
        float h = 0.5f;
        // six faces: {normal, four corners CCW from outside}
        float[][][] faces = {
                {{0, 0, -1}, {h, h, -h}, {-h, h, -h}, {-h, -h, -h}, {h, -h, -h}},   // north
                {{0, 0, 1}, {-h, h, h}, {h, h, h}, {h, -h, h}, {-h, -h, h}},        // south
                {{-1, 0, 0}, {-h, h, -h}, {-h, h, h}, {-h, -h, h}, {-h, -h, -h}},   // west
                {{1, 0, 0}, {h, h, h}, {h, h, -h}, {h, -h, -h}, {h, -h, h}},        // east
                {{0, 1, 0}, {-h, h, -h}, {h, h, -h}, {h, h, h}, {-h, h, h}},        // up
                {{0, -1, 0}, {-h, -h, h}, {h, -h, h}, {h, -h, -h}, {-h, -h, -h}}    // down
        };
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        for (float[][] face : faces) {
            float[] n = face[0];
            for (int i = 1; i <= 4; i++) {
                float[] c = face[i];
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
