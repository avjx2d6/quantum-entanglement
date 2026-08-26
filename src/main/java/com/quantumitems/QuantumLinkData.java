package com.quantumitems;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * Attached to every window (linked ItemStack) of a quantum network.
 * The stack itself carries no state beyond this pair of ids; the shared
 * pool lives in {@link QuantumNetworks}.
 */
public record QuantumLinkData(int networkId, int memberId) {
    public static final Codec<QuantumLinkData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("network").forGetter(QuantumLinkData::networkId),
            Codec.INT.fieldOf("member").forGetter(QuantumLinkData::memberId)
    ).apply(instance, QuantumLinkData::new));

    public static final StreamCodec<ByteBuf, QuantumLinkData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, QuantumLinkData::networkId,
            ByteBufCodecs.VAR_INT, QuantumLinkData::memberId,
            QuantumLinkData::new
    );

    /**
     * A colour belonging to this network and no other.
     *
     * <p>The id was printed in one flat cyan, so telling which of the windows
     * in a chest belong together meant reading three digits off each of them.
     * A hue hashed from the id answers it at a glance, and costs nothing: the
     * number is still there for anyone who wants it exactly.
     *
     * <p>Walked by the golden ratio rather than hashed. A hash scatters, which
     * is not the same as spreading: it put networks 1, 4 and 5 on three violets
     * a twelfth of the wheel apart, and consecutive ids are exactly the case
     * that matters, because they are the ones a player makes in one session.
     * Stepping the hue by φ leaves the ten closest ids no nearer than a
     * twentieth of the wheel — nearly five times the gap — and it never
     * revisits a hue however far the ids run.
     *
     * <p>Saturation is held at half and value at full, which keeps every hue in
     * the range legible against a tooltip's near-black.
     */
    private static final float GOLDEN_RATIO = 0.618033988f;

    public int colour() {
        return Mth.hsvToRgb((networkId * GOLDEN_RATIO) % 1.0f, 0.5f, 1.0f);
    }

    public Component tooltipLine() {
        return Component.translatable("tooltip.quantumitems.link_id",
                        String.format("%03d", networkId), memberId)
                .withStyle(style -> style.withColor(colour()));
    }
}
