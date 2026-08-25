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
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Faithful port of Create's DepotRenderer for a single held stack: the item
 * LIES flat on the pedestal, slides in from the side the player stood on
 * (offset = (.5 − beltPosition) toward the inserted-from side), larger
 * counts pile up as fanned log2 layers, block items scatter as miniatures.
 *
 * One deliberate deviation: Create renders onto the depot's recessed tray
 * (base height 15/16 with items sinking to the tray surface); our resonator
 * is a full cube, so the base heights compensate to land items ON its top.
 */
public class ResonatorRenderer implements BlockEntityRenderer<ResonatorBlockEntity> {

    public ResonatorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ResonatorBlockEntity resonator, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (resonator.getLevel() == null) {
            return;
        }
        renderInnerEye(resonator, partialTick, poseStack, buffers, packedOverlay);
        ItemStack stack = resonator.displayedItem();
        if (stack.isEmpty()) {
            return;
        }
        // Blockstate gate: the server mirrors occupancy into OCCUPIED every
        // tick over the never-lost blockstate channel. A stale client-side
        // item copy (missed BE packet) must not be drawn. Only in a real
        // client level — fake worlds (Ponder) have no server tick to raise
        // the flag, their laid items must still render.
        net.minecraft.world.level.block.state.BlockState state = resonator.getBlockState();
        if (resonator.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel
                && state.hasProperty(com.quantumitems.block.ResonatorBlock.OCCUPIED)
                && !state.getValue(com.quantumitems.block.ResonatorBlock.OCCUPIED)) {
            return;
        }
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, resonator.getLevel(), null, 0);
        boolean blockItem = model.isGui3d();
        int light = LevelRenderer.getLightColor(resonator.getLevel(), resonator.getBlockPos().above());

        // Create's depot uses one base height for all item kinds and lets the
        // FIXED display transform land them on its tray, which works out to
        // 2/16 above the surface the item appears to rest on. Ours is the
        // recessed tray plate inside the rim, at 15.5/16.
        poseStack.pushPose();
        poseStack.translate(0.5f, 15.5f / 16f + 2 / 16f, 0.5f);

        // Slide-in from the inserted side: Create's (.5 − beltPosition) offset.
        Direction from = resonator.insertedFrom();
        float offset = resonator.slideOffset(partialTick);
        if (from != null && from.getAxis().isHorizontal()) {
            Vec3 offsetVec = Vec3.atLowerCornerOf(from.getOpposite().getNormal()).scale(0.5f - offset);
            poseStack.translate(offsetVec.x, offsetVec.y, offsetVec.z);
        }

        int angle = resonator.layAngle();
        Random r = new Random(0);
        int count = Mth.log2(stack.getCount()) / 2;

        poseStack.mulPose(Axis.YP.rotationDegrees(angle));
        for (int i = 0; i <= count; i++) {
            poseStack.pushPose();
            if (blockItem) {
                poseStack.translate(r.nextFloat() * 0.0625f * i, 0, r.nextFloat() * 0.0625f * i);
            }
            poseStack.scale(0.5f, 0.5f, 0.5f);
            if (!blockItem) {
                poseStack.translate(0, -3 / 16f, 0);
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
            }
            itemRenderer.render(stack, ItemDisplayContext.FIXED, false, poseStack, buffers,
                    light, packedOverlay, model);
            poseStack.popPose();

            if (!blockItem) {
                poseStack.mulPose(Axis.YP.rotationDegrees(10));
            }
            // Tighter pile than Create's depot: our flat items carry no tray
            // recess to sink into, so full 1/16 steps read as floating layers.
            poseStack.translate(0, blockItem ? 1 / 64d : 1 / 32d, 0);
        }
        poseStack.popPose();
    }

    /**
     * The eye inside the hollow column: crossed sprites give it presence
     * from every viewing angle through the wall gaps (real parallax). It
     * drifts lazily in idle and wakes — faster, fullbright — while the
     * circle is locked by a running ritual.
     */
    private void renderInnerEye(ResonatorBlockEntity resonator, float partialTick, PoseStack poseStack,
                                MultiBufferSource buffers, int packedOverlay) {
        float time = resonator.getLevel().getGameTime() + partialTick;
        boolean awake = resonator.isLockedByRitual();
        float speed = awake ? 6.0f : 1.0f;
        // Height, size and both gem turns come from the cube in
        // resonator.bbmodel, which the amethyst frame at y 5..7 is built around.
        poseStack.pushPose();
        poseStack.translate(0.5, ObserverEyeRender.CORE_HEIGHT
                + Mth.sin(time / 14.0f) * 0.03f, 0.5);
        float spin = SpinClock.advance(resonator.getBlockPos(), time, speed);
        int light = SpinClock.lit(
                LevelRenderer.getLightColor(resonator.getLevel(), resonator.getBlockPos()),
                SpinClock.glow(resonator.getBlockPos(), awake ? 1.0f : 0.0f));
        poseStack.mulPose(Axis.YP.rotationDegrees(spin + ObserverEyeRender.GEM_YAW));
        poseStack.mulPose(Axis.ZP.rotationDegrees(ObserverEyeRender.GEM_TIP));
        poseStack.scale(ObserverEyeRender.SIZE, ObserverEyeRender.SIZE, ObserverEyeRender.SIZE);
        ObserverEyeRender.renderEyeCube(poseStack, buffers, light);
        poseStack.popPose();
    }
}
