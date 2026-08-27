package com.quantumitems.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.GraphicsStatus;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Render types the mod needs and vanilla does not offer. Subclassing
 * {@link RenderType} is the only way to reach {@code create} and the state
 * shards; the class is never instantiated.
 */
public final class QuantumRenderTypes extends RenderType {

    private QuantumRenderTypes() {
        super(null, null, null, 0, false, false, null, null);
        throw new UnsupportedOperationException();
    }

    /**
     * Untextured additive geometry for every strand in the mod.
     *
     * <p>Not vanilla's {@code lightning()}, which draws into the WEATHER target
     * rather than the main one — that made the beams composite oddly over the
     * scene and blink out at the edge of view — and whose write mask includes
     * depth, so a beam would silently occlude whatever was behind it.
     *
     * <p>Depth is tested but not written: the beam is hidden by blocks in front
     * of it, and the far side of the tube shows through the near side, which is
     * what makes it read as light rather than as painted metal.
     *
     * <h4>Under a shader pack this is not the shader that runs</h4>
     *
     * <p>Iris swaps the ShaderInstance behind every {@code RENDERTYPE_*_SHADER}
     * for a program of the pack's own, picked by WHICH vanilla shader the render
     * type asked for. So borrowing the lightning shader hands every strand to
     * the pack's {@code gbuffers_lightning} — and packs are entitled to do
     * anything they like in there, because vanilla only ever draws one thing
     * with it. Photon does exactly this, verbatim:
     *
     * <pre>{@code
     * #if defined PROGRAM_GBUFFERS_LIGHTNING
     *     if (material_mask == MATERIAL_DRAGON_BEAM) { ... } else {
     *         material.albedo   = vec3(1.0);
     *         material.emission = vec3(1.0);
     *     }
     * #endif
     * }</pre>
     *
     * <p>which is why the beams come out pure white there and are fine on
     * Complementary. Nothing on our side can override it: that program runs
     * after ours.
     *
     * <p>Borrowing the BEACON BEAM shader instead was tried, on the reasoning
     * that it is the one vanilla program meaning "a coloured column of light"
     * and packs therefore keep its colour. On Photon the beams vanished
     * altogether — its beacon program is a deferred SOLID one, and a surface
     * that writes no depth has nothing for the deferred passes to find. White
     * beams beat no beams, so the lightning shader stays and
     * {@link ShaderWarning} says so in chat instead. Getting this genuinely
     * right needs a pack-side patch or a render path that writes depth, and
     * neither is a thing to guess at without a GPU to look at.
     */
    public static final RenderType RITUAL_BEAM = create(
            "quantumitems_ritual_beam",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setWriteMaskState(COLOR_WRITE)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    /**
     * Which render stage a strand belongs in, given that it writes no depth.
     *
     * <p>It used to be drawn at AFTER_PARTICLES, and clouds went in front of
     * it — from underneath, which is nonsense. Clouds are drawn after that
     * stage and they write depth AND colour ({@code COLOR_DEPTH_WRITE}), so a
     * cloud tests against whatever is behind the strand — sky, or distant
     * terrain — never against the strand itself, and wins every time however
     * close the strand is. Nothing was wrong with the depth TEST; the problem
     * is that a surface writing no depth is invisible to everything drawn
     * after it.
     *
     * <p>AFTER_WEATHER is past the clouds, so their depth is already in the
     * buffer and the test resolves both ways round: a cloud overhead leaves
     * the strand alone, a cloud genuinely between camera and strand hides it.
     *
     * <p>Except under Fabulous graphics, where that stage runs with the
     * WEATHER target bound and the transparency chain about to composite, and
     * a main-target draw there lands in the wrong place. Fabulous keeps the
     * old stage and the old cloud quirk — a bad shuffle beats a wrong buffer.
     */
    public static boolean isStrandStage(RenderLevelStageEvent.Stage stage) {
        boolean fabulous = Minecraft.getInstance().options.graphicsMode().get() == GraphicsStatus.FABULOUS;
        return stage == (fabulous ? RenderLevelStageEvent.Stage.AFTER_PARTICLES
                : RenderLevelStageEvent.Stage.AFTER_WEATHER);
    }
}
