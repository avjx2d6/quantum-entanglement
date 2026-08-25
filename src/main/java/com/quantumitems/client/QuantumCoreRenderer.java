package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
        RitualHumSound.ensurePlaying(core); // client-owned hum with smooth fades
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        float time = core.getLevel().getGameTime() + partialTick;
        boolean running = core.isRitualRunning();

        // --- the observer inside the lower housing ---
        // Degrees per tick. The crescendo used to reach 29, which is most of a
        // revolution every twelve frames — past that the gem stops reading as a
        // turning solid and just strobes. 14 is about 0.8 turns a second, fast
        // enough to look driven and slow enough to still be a shape.
        float observerSpeed = switch (core.phase()) {
            case IDLE -> 1.2f;
            case CRESCENDO -> 5.0f + 9.0f * core.phaseAge() / QuantumCoreBlockEntity.CRESCENDO_TICKS;
            case SUCCESS, FAILURE -> 0.8f;
            default -> 5.0f;
        };
        int observerLight = running ? 0xF000F0
                : LevelRenderer.getLightColor(core.getLevel(), core.getBlockPos());
        poseStack.pushPose();
        // Height, size and the two gem turns all come from the cube in
        // quantum_core_lower_e.bbmodel; only the bob and the spin are ours.
        poseStack.translate(0.5, ObserverEyeRender.CORE_HEIGHT
                + Mth.sin(time / 16.0f) * 0.03f, 0.5);
        ObserverEyeRender.renderCorona(poseStack, buffers, time, running ? 1.0f : 0.25f);
        poseStack.mulPose(Axis.YP.rotationDegrees(
                SpinClock.advance(core.getBlockPos(), time, observerSpeed) + ObserverEyeRender.GEM_YAW));
        poseStack.mulPose(Axis.ZP.rotationDegrees(ObserverEyeRender.GEM_TIP));
        poseStack.scale(ObserverEyeRender.SIZE, ObserverEyeRender.SIZE, ObserverEyeRender.SIZE);
        ObserverEyeRender.renderEyeCube(poseStack, buffers, observerLight);
        poseStack.popPose();

        // --- the shard inside the upper frame ---
        ItemStack shard = core.displayedShard();
        if (shard.isEmpty()) {
            return;
        }
        float age = core.phaseAge() + partialTick;
        // Continuous piecewise-integral angle across the scripted phases:
        // slow 2°/t through connect/scan/judgement, ramp 4→30°/t in the
        // crescendo, wind-down in the aftermath, lazy drift when inert.
        float slowEnd = 2.0f * (QuantumCoreBlockEntity.CONNECTING_TICKS
                + QuantumCoreBlockEntity.SCANNING_TICKS + QuantumCoreBlockEntity.JUDGEMENT_TICKS);
        // No SUCCESS/FAILURE arm: both are entered only after the shard has
        // already been burned (tick() and cancelRitual() clear it first), so
        // the early return above fires and there is nothing left to spin.
        float angle = switch (core.phase()) {
            case CONNECTING, SCANNING, JUDGEMENT ->
                    2.0f * (QuantumCoreBlockEntity.phaseOffset(core.phase()) + age);
            case CRESCENDO -> slowEnd + 4.0f * age
                    + 13.0f * age * age / QuantumCoreBlockEntity.CRESCENDO_TICKS;
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
