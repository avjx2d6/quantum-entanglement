package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quantumitems.ModRegistry;
import com.quantumitems.block.QuantumCoreBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Two live layers, both driven by the ritual state machine:
 * — the OBSERVER: an Eye of Elsewhere floating inside the hollow lower
 *   housing (crossed sprites — visible through the wall gaps from any
 *   angle, with real parallax), drifting lazily in idle and whipping up
 *   during a ritual;
 * — the committed shard hovering inside the upper frame, spin angle being
 *   the piecewise INTEGRAL of the speed ramp so it never jumps between
 *   phases; it vanishes at the verdict flash.
 */
public class QuantumCoreRenderer implements BlockEntityRenderer<QuantumCoreBlockEntity> {
    private final ItemStack observerEye = new ItemStack(ModRegistry.EYE_OF_ELSEWHERE.get());

    public QuantumCoreRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(QuantumCoreBlockEntity blockEntity) {
        return true; // the shard floats a block above the BE position
    }

    @Override
    public void render(QuantumCoreBlockEntity core, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (core.getLevel() == null) {
            return;
        }
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        float time = core.getLevel().getGameTime() + partialTick;
        boolean running = core.isRitualRunning();

        // --- the observer inside the lower housing ---
        float observerSpeed = running ? 9.0f : 1.2f;
        int observerLight = running ? 0xF000F0
                : LevelRenderer.getLightColor(core.getLevel(), core.getBlockPos());
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5 + Mth.sin(time / 16.0f) * 0.03f, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * observerSpeed));
        poseStack.scale(0.55f, 0.55f, 0.55f);
        itemRenderer.renderStatic(observerEye, ItemDisplayContext.FIXED, observerLight, packedOverlay,
                poseStack, buffers, core.getLevel(), 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(90));
        itemRenderer.renderStatic(observerEye, ItemDisplayContext.FIXED, observerLight, packedOverlay,
                poseStack, buffers, core.getLevel(), 0);
        poseStack.popPose();

        // --- the shard inside the upper frame ---
        ItemStack shard = core.displayedShard();
        if (shard.isEmpty()) {
            return;
        }
        float age = core.phaseAge() + partialTick;
        float chargeEnd = 2.0f * QuantumCoreBlockEntity.CHARGING_TICKS + 8.0f * QuantumCoreBlockEntity.CHARGING_TICKS;
        float angle = switch (core.phase()) {
            case CHARGING -> 2.0f * age + 8.0f * age * age / QuantumCoreBlockEntity.CHARGING_TICKS;
            case JUDGEMENT -> chargeEnd + 18.0f * age;
            default -> time * 1.5f; // inert shard on an unfinished machine: lazy drift
        };
        int light = LevelRenderer.getLightColor(core.getLevel(), core.getBlockPos().above());
        poseStack.pushPose();
        poseStack.translate(0.5, 1.4 + Mth.sin(time / 10.0f) * 0.04f, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(angle));
        poseStack.scale(0.7f, 0.7f, 0.7f);
        itemRenderer.renderStatic(shard, ItemDisplayContext.GROUND, light, packedOverlay,
                poseStack, buffers, core.getLevel(), 0);
        poseStack.popPose();
    }
}
