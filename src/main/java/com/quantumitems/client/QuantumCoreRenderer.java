package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quantumitems.block.QuantumCoreBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The committed shard hovers above the core and spins with the ritual: it
 * accelerates through CHARGING, blurs through JUDGEMENT, and either flares
 * out (consumed at the verdict tick) or was never there at all.
 */
public class QuantumCoreRenderer implements BlockEntityRenderer<QuantumCoreBlockEntity> {
    private final ItemRenderer itemRenderer;

    public QuantumCoreRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(QuantumCoreBlockEntity core, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        ItemStack shard = core.displayedShard();
        if (shard.isEmpty() || core.getLevel() == null) {
            return;
        }
        float age = core.phaseAge() + partialTick;
        // Spin accelerates through CHARGING and stays frantic in JUDGEMENT.
        float speed = switch (core.phase()) {
            case CHARGING -> 2.0f + 16.0f * age / QuantumCoreBlockEntity.CHARGING_TICKS;
            case JUDGEMENT -> 20.0f;
            default -> 1.0f;
        };
        float time = core.getLevel().getGameTime() + partialTick;
        int light = LevelRenderer.getLightColor(core.getLevel(), core.getBlockPos().above());
        poseStack.pushPose();
        poseStack.translate(0.5, 1.35 + Mth.sin(time / 10.0f) * 0.04f, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * speed));
        poseStack.scale(0.7f, 0.7f, 0.7f);
        itemRenderer.renderStatic(shard, ItemDisplayContext.GROUND, light, packedOverlay,
                poseStack, buffers, core.getLevel(), 0);
        poseStack.popPose();
    }
}
