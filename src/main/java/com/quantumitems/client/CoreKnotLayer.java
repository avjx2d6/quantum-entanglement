package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quantumitems.QuantumItemsMod;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The knot hovering inside a Quantum Core, drawn at the end of the frame with
 * the rest of the strands instead of in the block-entity pass.
 *
 * <p>It has to be here for the same reason the beams do. A strand writes no
 * depth ({@link QuantumRenderTypes#RITUAL_BEAM} deliberately: that is what
 * lets the far wall of the tube show through the near one), and a surface that
 * writes no depth is invisible to everything drawn AFTER it — whatever comes
 * later tests against the terrain behind the strand, wins, and paints straight
 * over it. Block entities are drawn early, so translucent terrain, particles,
 * clouds and weather all came afterwards and each one could put a band across
 * the knot. Moving the draw to the stage the beams already use puts it past
 * all of them, and the depth TEST — which was never the problem — still hides
 * it behind anything genuinely in front.
 *
 * <p>The block-entity renderer still decides WHETHER and HOW the knot is
 * drawn; it just leaves the drawing to this. That keeps vanilla's culling and
 * chunk visibility for free rather than re-deriving which cores are on screen,
 * because the queue is filled and emptied inside one frame: block entities
 * render before either candidate stage, and one of the two always fires.
 */
@EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
public final class CoreKnotLayer {

    private record Pending(BlockPos pos, double height, float angle, float fury, float grow,
                           int light, ItemStack stack) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private CoreKnotLayer() {
    }

    /**
     * @param height centre of the knot above the block's own corner, bob included
     * @param angle  degrees around Y
     * @param fury   how hard it is being worked, 0..1 — see
     *               {@link EntangledKnotRenderer#agitation}
     */
    static void submit(BlockPos pos, double height, float angle, float fury, float grow,
                       int light, ItemStack stack) {
        PENDING.add(new Pending(pos.immutable(), height, angle, fury, grow, light, stack));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!QuantumRenderTypes.isStrandStage(event.getStage()) || PENDING.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            PENDING.clear();
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        ShaderWarning.checkOnce();

        for (Pending knot : PENDING) {
            poseStack.pushPose();
            poseStack.translate(knot.pos.getX() + 0.5 - camera.x,
                    knot.pos.getY() + knot.height - camera.y,
                    knot.pos.getZ() + 0.5 - camera.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(knot.angle));
            poseStack.scale(knot.grow, knot.grow, knot.grow);
            EntangledKnotRenderer.agitation = knot.fury;
            try {
                mc.getItemRenderer().renderStatic(knot.stack, ItemDisplayContext.GROUND,
                        knot.light, OverlayTexture.NO_OVERLAY, poseStack, buffer, mc.level, 0);
            } finally {
                // Never leave it set: a throw in there would have every knot in
                // the world thrashing for the rest of the session.
                EntangledKnotRenderer.agitation = 0.0f;
            }
            poseStack.popPose();
        }
        PENDING.clear();
        buffer.draw();
    }
}
