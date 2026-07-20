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
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, resonator.getLevel(), null, 0);
        boolean blockItem = model.isGui3d();
        int light = LevelRenderer.getLightColor(resonator.getLevel(), resonator.getBlockPos().above());

        // Create's depot uses one base height (15/16) for all item kinds and
        // lets the FIXED display transform land them on its tray; our top
        // face sits 3/16 higher than the depot tray, hence 15/16 + 3/16.
        poseStack.pushPose();
        poseStack.translate(0.5f, 15 / 16f + 3 / 16f, 0.5f);

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
        float speed = awake ? 8.0f : 1.0f;
        int light = awake ? 0xF000F0
                : LevelRenderer.getLightColor(resonator.getLevel(), resonator.getBlockPos());
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5 + Mth.sin(time / 14.0f) * 0.03f, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * speed));
        poseStack.mulPose(Axis.XP.rotationDegrees(35.3f)); // gem tilt
        poseStack.mulPose(Axis.ZP.rotationDegrees(45.0f));
        poseStack.scale(0.28f, 0.28f, 0.28f);
        ObserverEyeRender.renderEyeCube(poseStack, buffers, light);
        poseStack.popPose();
    }
}
