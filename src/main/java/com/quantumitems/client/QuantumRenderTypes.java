package com.quantumitems.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

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
     * Untextured additive geometry for the ritual beams.
     *
     * <p>Not vanilla's {@code lightning()}, which draws into the WEATHER target
     * rather than the main one — that made the beams composite oddly over the
     * scene and blink out at the edge of view — and whose write mask includes
     * depth, so a beam would silently occlude whatever was behind it.
     *
     * <p>Depth is tested but not written: the beam is hidden by blocks in front
     * of it, and the far side of the tube shows through the near side, which is
     * what makes it read as light rather than as painted metal.
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
}
