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
        float time = core.getLevel().getGameTime() + partialTick;
        // Continuous angle: the piecewise INTEGRAL of the speed ramp, so the
        // rotation never jumps when the phase (and thus the speed) changes.
        // IDLE = an inert shard lying on an unfinished machine: lazy drift.
        float chargeEnd = 2.0f * QuantumCoreBlockEntity.CHARGING_TICKS + 8.0f * QuantumCoreBlockEntity.CHARGING_TICKS;
        float angle = switch (core.phase()) {
            case CHARGING -> 2.0f * age + 8.0f * age * age / QuantumCoreBlockEntity.CHARGING_TICKS;
            case JUDGEMENT -> chargeEnd + 18.0f * age;
            default -> time * 1.5f;
        };
        int light = LevelRenderer.getLightColor(core.getLevel(), core.getBlockPos().above());
        poseStack.pushPose();
        poseStack.translate(0.5, 1.35 + Mth.sin(time / 10.0f) * 0.04f, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(angle));
        poseStack.scale(0.7f, 0.7f, 0.7f);
        itemRenderer.renderStatic(shard, ItemDisplayContext.GROUND, light, packedOverlay,
                poseStack, buffers, core.getLevel(), 0);
        poseStack.popPose();
    }
}
