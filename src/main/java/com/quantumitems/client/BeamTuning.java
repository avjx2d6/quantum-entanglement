package com.quantumitems.client;

/**
 * Live-tunable knobs for the ritual beams, client side only. Everything here is
 * read fresh every frame, so {@code /qbeam} changes land instantly — the point
 * is to judge the feel in the world, not to reload anything.
 *
 * <p>This is tuning scaffolding: once a look is chosen the winning numbers
 * become constants in {@link RitualBeamRenderer} and this class goes away.
 */
public final class BeamTuning {

    /** How a beam is drawn between the core and a resonator. */
    public enum Style {
        /** What the mod shipped: coloured dust sprayed along the segment. */
        PARTICLES,
        /** Exponential random walk, the reference values: twitchy electric arc. */
        TWITCHY,
        /** Same walk, slower and narrower — it flows instead of snapping. */
        CALM
    }

    public static Style style = Style.CALM;

    /** Overall deflection from the straight line. */
    public static float amplitude = 0.9f;
    /** Segments per beam; our beams are always 2.83 blocks, so this is just fixed. */
    public static int nodes = 12;
    /** Random walk pull-back per tick. Higher drifts slower AND wider. */
    public static float decay = 0.8f;
    /** Random walk kick per tick. */
    public static float spread = 0.7f;
    /**
     * Line width in blocks. Segments are separate cuboids, so their end faces
     * meet at an angle at every node — the reference hides that by staying
     * thin (0.6/16), and so do we. Widen it and the joints start showing.
     */
    public static float width = 0.6f / 16f;

    private BeamTuning() {
    }

    public static void applyPreset(Style preset) {
        style = preset;
        switch (preset) {
            case TWITCHY -> { decay = 0.5f; spread = 3.0f; nodes = 8; amplitude = 0.5f; }
            case CALM -> { decay = 0.8f; spread = 0.7f; nodes = 12; amplitude = 0.9f; }
            default -> { }
        }
    }

    /** One line of the current state, for command feedback. */
    public static String describe() {
        return "style=" + style
                + " amp=" + String.format("%.2f", amplitude)
                + " nodes=" + nodes
                + " decay=" + String.format("%.2f", decay)
                + " spread=" + String.format("%.2f", spread)
                + " width=" + String.format("%.3f", width);
    }
}
