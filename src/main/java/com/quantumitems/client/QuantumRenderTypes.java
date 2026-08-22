package com.quantumitems.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * Render types the mod needs and vanilla does not offer.
 *
 * <p>Subclassing {@link RenderType} is the only way to reach {@code create} and
 * the shard constants; the class is never instantiated.
 */
public final class QuantumRenderTypes extends RenderType {

    private QuantumRenderTypes() {
        super(null, null, null, 0, false, false, null, null);
        throw new UnsupportedOperationException();
    }

    /**
     * Additive, untextured, world-space geometry for the ritual beams.
     *
     * <p>Vanilla's {@code lightning()} looks right at a glance but is wrong for
     * this in two ways: it draws into the WEATHER target rather than the main
     * one, so the result composites over the scene and flickers out at the edge
     * of view, and its write mask includes depth, so a glow halo silently
     * occludes whatever is behind it. This one draws into the main target,
     * tests depth so nearer blocks and items cover it properly, and writes
     * colour only.
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
