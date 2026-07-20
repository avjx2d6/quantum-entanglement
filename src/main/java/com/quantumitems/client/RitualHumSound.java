package com.quantumitems.client;

import com.quantumitems.ModRegistry;
import com.quantumitems.block.QuantumCoreBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-owned looping hum for the early ritual phases, with smooth fade-in
 * and fade-out. A fire-and-forget server playSound cannot fade — on a
 * cancelled ritual the old repeating hum just cut off mid-note (playtest
 * complaint). This instance follows the core's synced phase every tick:
 * ramps up while the ritual approaches, drains away the moment the phase
 * leaves the hum window (crescendo, cancel, broken machine — all fade).
 */
public class RitualHumSound extends AbstractTickableSoundInstance {
    private static final Map<BlockPos, RitualHumSound> ACTIVE = new HashMap<>();
    private static final float TARGET_VOLUME = 0.9f;
    private static final float FADE_IN = 0.045f;   // ~1s to full
    private static final float FADE_OUT = 0.06f;   // ~0.75s to silence

    private final QuantumCoreBlockEntity core;

    /** Called from the renderer every frame; starts one instance per core. */
    public static void ensurePlaying(QuantumCoreBlockEntity core) {
        if (!humPhase(core.phase())) {
            return;
        }
        BlockPos pos = core.getBlockPos();
        RitualHumSound existing = ACTIVE.get(pos);
        if (existing != null && !existing.isStopped()) {
            return;
        }
        RitualHumSound sound = new RitualHumSound(core);
        ACTIVE.put(pos, sound);
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    private static boolean humPhase(QuantumCoreBlockEntity.Phase phase) {
        return phase == QuantumCoreBlockEntity.Phase.CONNECTING
                || phase == QuantumCoreBlockEntity.Phase.SCANNING
                || phase == QuantumCoreBlockEntity.Phase.JUDGEMENT;
    }

    private RitualHumSound(QuantumCoreBlockEntity core) {
        super(ModRegistry.RITUAL_HUM.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.core = core;
        this.looping = true;
        this.delay = 0;
        // Non-zero start: the sound engine culls instances that BEGIN at
        // volume zero and never actually starts them (the silent-hum bug).
        this.volume = 0.08f;
        this.x = core.getBlockPos().getX() + 0.5;
        this.y = core.getBlockPos().getY() + 0.5;
        this.z = core.getBlockPos().getZ() + 0.5;
    }

    @Override
    public void tick() {
        boolean keep = !core.isRemoved() && humPhase(core.phase());
        if (keep) {
            volume = Math.min(TARGET_VOLUME, volume + FADE_IN);
        } else {
            volume -= FADE_OUT;
            if (volume <= 0.0f) {
                ACTIVE.remove(core.getBlockPos(), this);
                stop();
            }
        }
    }
}
