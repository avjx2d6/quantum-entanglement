package com.quantumitems;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

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

    public Component tooltipLine() {
        return Component.translatable("tooltip.quantumitems.link_id",
                        String.format("%03d", networkId), memberId)
                .withStyle(ChatFormatting.AQUA);
    }
}
