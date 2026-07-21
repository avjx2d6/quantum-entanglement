package com.quantumitems;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants the mod's code-driven advancements. The three ritual-outcome
 * advancements use a {@code minecraft:impossible} criterion named "trigger" in
 * their JSON — the player can never satisfy it themselves, only this award
 * from the ritual state machine can.
 */
public final class QuantumAdvancements {
    private QuantumAdvancements() {
    }

    public static final String ENTANGLED = "entanglement/entangled";
    public static final String QUARTET = "entanglement/quartet";
    public static final String YOUR_OWN_FAULT = "entanglement/your_own_fault";

    public static void award(ServerPlayer player, String path) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder holder = server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(QuantumItemsMod.MOD_ID, path));
        if (holder != null) {
            player.getAdvancements().award(holder, "trigger");
        }
    }
}
