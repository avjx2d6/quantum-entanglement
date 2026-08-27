package com.quantumitems.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * One line in chat, the first time a strand is drawn while a shader pack is
 * running, saying that the beams may not look right.
 *
 * <p>Not an apology and not a bug report. A strand is additive geometry that
 * writes no depth, and under Iris the program that actually draws it belongs to
 * the pack, chosen by which vanilla core shader the render type asked for — see
 * {@link QuantumRenderTypes#RITUAL_BEAM}, which has the reasoning and the
 * relevant lines of Photon's source. Every option available to us is somebody's
 * program: the lightning one, which Photon forces to white, or the beacon one,
 * where it disappears entirely. We cannot pick an outcome, only a
 * neighbourhood, so the honest thing is to tell the player what they are
 * looking at rather than let them hunt for a broken world.
 *
 * <p>Iris is reached by reflection through its stable v0 API rather than as a
 * dependency, because it is an optional mod that most players will not have and
 * a hard reference would have to be shaded, shipped and kept in step for the
 * sake of one boolean.
 */
public final class ShaderWarning {

    /** The v0 API is Iris's own compatibility promise; the class moved in 1.20, hence two names. */
    private static final String[] IRIS_API = {
            "net.irisshaders.iris.api.v0.IrisApi",
            "net.coderbot.iris.api.v0.IrisApi",
    };

    private static boolean said;

    private ShaderWarning() {
    }

    /** A new world is a new chance to have turned shaders on or off. */
    public static void forgetAll() {
        said = false;
    }

    /**
     * Called from each of the three places a strand is drawn, once they know
     * they have something to draw — so a player who never builds the machine is
     * never told anything, and one who does is told the moment it could matter.
     */
    public static void checkOnce() {
        if (said) {
            return;
        }
        said = true;   // set first: a warning that cannot be shown is not worth retrying every frame
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !shadersInUse()) {
            return;
        }
        mc.player.displayClientMessage(
                Component.translatable("message.quantumitems.shaders").withStyle(ChatFormatting.GRAY),
                false);
    }

    private static boolean shadersInUse() {
        for (String name : IRIS_API) {
            try {
                Class<?> api = Class.forName(name);
                Object instance = api.getMethod("getInstance").invoke(null);
                return (Boolean) api.getMethod("isShaderPackInUse").invoke(instance);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not installed, or a version that renamed something. Either way
                // there is nothing to warn about and nothing to log: this runs on
                // every client that has never heard of Iris.
            }
        }
        return false;
    }
}
