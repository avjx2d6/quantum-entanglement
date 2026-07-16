package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quantumitems.block.ResonatorBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** The laid-out stack hovers and slowly turns above the resonator, depot-style. */
public class ResonatorRenderer implements BlockEntityRenderer<ResonatorBlockEntity> {
    private final ItemRenderer itemRenderer;

    public ResonatorRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ResonatorBlockEntity resonator, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        ItemStack stack = resonator.displayedItem();
        if (stack.isEmpty() || resonator.getLevel() == null) {
            return;
        }
        float time = resonator.getLevel().getGameTime() + partialTick;
        int light = LevelRenderer.getLightColor(resonator.getLevel(), resonator.getBlockPos().above());
        poseStack.pushPose();
        poseStack.translate(0.5, 1.3 + Mth.sin(time / 12.0f) * 0.05f, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * 1.5f));
        poseStack.scale(0.6f, 0.6f, 0.6f);
        itemRenderer.renderStatic(stack, ItemDisplayContext.GROUND, light, packedOverlay,
                poseStack, buffers, resonator.getLevel(), 0);
        poseStack.popPose();
    }
}
