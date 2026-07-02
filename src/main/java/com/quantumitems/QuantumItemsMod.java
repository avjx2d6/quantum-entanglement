package com.quantumitems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(QuantumItemsMod.MOD_ID)
public class QuantumItemsMod {
    public static final String MOD_ID = "quantumitems";

    public QuantumItemsMod(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
    }
}
