package com.quantumitems.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.client.QuantumRenderTypes;
import com.quantumitems.client.StrandGeometry;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Strands painted by hand with the {@link com.quantumitems.debug.StrandWandItem}.
 *
 * <p>SCAFFOLDING — goes with the wand. It exists to look at the beam geometry
 * anywhere except inside a ritual, at any length and any colour.
 *
 * <p>Client-side only, which is what keeps it small: the list lives here, the
 * renderer reads it here, and nothing is saved or sent. It is dropped when the
 * level unloads, like every other client-side cache in the mod.
 */
@EventBusSubscriber(modid = QuantumItemsMod.MOD_ID, value = Dist.CLIENT)
public final class PaintedStrands {

    private static final int[] PALETTE = {
            0x21BED9,   // the ritual's cyan
            0x8243E0,   // its violet
            0xE0B01B,   // its amber
            0x1FD99A,   // its discharge green
            0xE02718,   // its red
            0xFFFFFF, 0xFF3DA6, 0x3DFF6E, 0x2B6BFF, 0xFF7A18,
    };
    private static final String[] NAMES = {
            "cyan", "violet", "amber", "green", "red",
            "white", "pink", "lime", "blue", "orange",
    };

    /** Nodes per block of strand, so a long one bends as much as a short one. */
    private static final float NODES_PER_BLOCK = 4.0f;
    private static final int MAX_NODES = 64;
    private static final int SIDES = 6;
    private static final float WIDTH = 0.6f / 16f;
    private static final float BRIGHTNESS = 0.5f;
    private static final float WALK_UNIT = 0.085f;
    private static final float DECAY = 0.8f;
    private static final float SPREAD = 0.7f;

    private record Strand(Vec3 from, Vec3 to, int rgb) {
    }

    private static final List<Strand> STRANDS = new ArrayList<>();
    private static Vec3 pending;
    private static int colour;

    // One walk for every strand on screen, as the knot does: they wander in
    // step, which costs one advance a tick however many are painted.
    private static final RandomSource RANDOM = RandomSource.create();
    private static final float[][] CUR = new float[MAX_NODES + 1][2];
    private static final float[][] PREV = new float[MAX_NODES + 1][2];
    private static long lastTick = Long.MIN_VALUE;

    private PaintedStrands() {
    }

    public static void clear() {
        STRANDS.clear();
        pending = null;
        say("cleared");
    }

    /** First click sets an end, second click strings a strand to it. */
    public static void mark(Vec3 point) {
        if (pending == null) {
            pending = point;
            say("first end set — click another block");
            return;
        }
        STRANDS.add(new Strand(pending, point, PALETTE[colour]));
        pending = null;
        say("strand " + STRANDS.size() + " in " + NAMES[colour]);
    }

    public static void nextColour() {
        colour = (colour + 1) % PALETTE.length;
        say("colour: " + NAMES[colour]);
    }

    private static void say(String what) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("strand wand: " + what).withStyle(ChatFormatting.AQUA), true);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            STRANDS.clear();
            pending = null;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!QuantumRenderTypes.isStrandStage(event.getStage()) || STRANDS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        advance(mc.level.getGameTime());
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        var consumer = buffer.getBuffer(QuantumRenderTypes.RITUAL_BEAM);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (Strand strand : STRANDS) {
            draw(poseStack, consumer, strand, partial);
        }
        poseStack.popPose();
        buffer.draw();
    }

    private static void advance(long now) {
        if (lastTick == Long.MIN_VALUE) {
            lastTick = now - 1;
        }
        long steps = Math.min(4, now - lastTick);
        for (long s = 0; s < steps; s++) {
            for (int i = 0; i <= MAX_NODES; i++) {
                PREV[i][0] = CUR[i][0];
                PREV[i][1] = CUR[i][1];
                CUR[i][0] = (CUR[i][0] + (RANDOM.nextFloat() - 0.5f) * SPREAD) * DECAY;
                CUR[i][1] = (CUR[i][1] + (RANDOM.nextFloat() - 0.5f) * SPREAD) * DECAY;
            }
        }
        lastTick = now;
    }

    private static void draw(PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                             Strand strand, float partial) {
        Vec3 span = strand.to.subtract(strand.from);
        double length = span.length();
        if (length < 1.0e-3) {
            return;
        }
        int n = Mth.clamp(Math.round((float) length * NODES_PER_BLOCK), 4, MAX_NODES);
        Vec3 dir = span.scale(1 / length);
        Vec3 u = dir.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0e-6) {
            u = dir.cross(new Vec3(1, 0, 0));
        }
        u = u.normalize();
        Vec3 v = dir.cross(u).normalize();

        Vec3[] pts = new Vec3[n + 1];
        float[] bright = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            // pinned at both ends, free in between — the same shape as a beam
            float slack = (i == 0 || i == n) ? 0 : WALK_UNIT;
            double ox = Mth.lerp(partial, PREV[i][0], CUR[i][0]) * slack;
            double oy = Mth.lerp(partial, PREV[i][1], CUR[i][1]) * slack;
            pts[i] = strand.from.add(span.scale(t)).add(u.scale(ox)).add(v.scale(oy));
            bright[i] = BRIGHTNESS;
        }
        StrandGeometry.tube(poseStack, consumer, pts, bright, strand.rgb, WIDTH * 0.5f, SIDES, false);
    }
}
