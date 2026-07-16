package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quantumitems.block.ResonatorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The laid-out stack LIES on the pedestal, Create-depot style (see
 * DepotRenderer): flat items rest rotated onto the top face and larger
 * counts render as a fanned pile (log2 layers), block items sit as slightly
 * scattered miniatures. The lay angle derives from the block position, so
 * every pedestal's stack rests a little differently — placed, not hovering.
 */
public class ResonatorRenderer implements BlockEntityRenderer<ResonatorBlockEntity> {

    public ResonatorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ResonatorBlockEntity resonator, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        ItemStack stack = resonator.displayedItem();
        if (stack.isEmpty() || resonator.getLevel() == null) {
            return;
        }
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, resonator.getLevel(), null, 0);
        boolean blockItem = model.isGui3d();
        int light = LevelRenderer.getLightColor(resonator.getLevel(), resonator.getBlockPos().above());
        int layers = Mth.log2(stack.getCount()) / 2;
        float baseAngle = (resonator.getBlockPos().hashCode() * 31) % 360;
        RandomSource scatter = RandomSource.create(resonator.getBlockPos().asLong());

        poseStack.pushPose();
        poseStack.translate(0.5, 1.0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(baseAngle));
        for (int i = 0; i <= layers; i++) {
            poseStack.pushPose();
            if (blockItem) {
                poseStack.translate(scatter.nextFloat() * 0.0625f * i, 0.25f + i * 0.02f,
                        scatter.nextFloat() * 0.0625f * i);
                poseStack.scale(0.5f, 0.5f, 0.5f);
            } else {
                poseStack.mulPose(Axis.YP.rotationDegrees(i * 10f));
                poseStack.translate(0, 0.018f + i * 0.031f, 0);
                poseStack.scale(0.5f, 0.5f, 0.5f);
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
            }
            itemRenderer.render(stack, ItemDisplayContext.FIXED, false, poseStack, buffers,
                    light, packedOverlay, model);
            poseStack.popPose();
        }
        poseStack.popPose();
    }
}
