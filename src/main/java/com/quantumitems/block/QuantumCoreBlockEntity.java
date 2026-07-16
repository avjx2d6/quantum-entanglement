package com.quantumitems.block;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumDebug;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The controller of the ritual circle: a 5×5 amethyst-block floor, four
 * resonators on its corners, the core on its center block.
 *
 * The ritual is a strict commitment: lay everything out first, then place a
 * shard — it launches immediately and burns the shard whether it succeeds or
 * fails (the rules are taught, not refunded). From launch to verdict every
 * stack in the circle is locked.
 *
 * The actual entanglement math happens in ONE tick at the end of JUDGEMENT,
 * so a chunk unload mid-ritual can never leave a half-applied network; the
 * phases before and after are pure theater.
 */
public class QuantumCoreBlockEntity extends SyncedBlockEntity {
    /** Corner offsets of the circle, in deterministic order (output windows fill in this order). */
    private static final BlockPos[] CORNERS = {
            new BlockPos(-2, 0, -2), new BlockPos(2, 0, -2),
            new BlockPos(-2, 0, 2), new BlockPos(2, 0, 2)};

    public enum Phase {
        IDLE, CHARGING, JUDGEMENT, SUCCESS, FAILURE;

        static Phase byOrdinal(int ordinal) {
            Phase[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
        }
    }

    public static final int CHARGING_TICKS = 40;
    public static final int JUDGEMENT_TICKS = 20;
    public static final int SUCCESS_TICKS = 20;
    public static final int FAILURE_TICKS = 30;

    private static final Vector3f COLOR_CHARGE = new Vector3f(0.75f, 0.55f, 1.0f);
    private static final Vector3f COLOR_INPUT = new Vector3f(0.35f, 0.9f, 1.0f);
    private static final Vector3f COLOR_FAIL = new Vector3f(1.0f, 0.2f, 0.15f);

    private Phase phase = Phase.IDLE;
    private int phaseAge;
    private ItemStack shard = ItemStack.EMPTY;

    public QuantumCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.QUANTUM_CORE_BE.get(), pos, state);
    }

    public Phase phase() {
        return phase;
    }

    public int phaseAge() {
        return phaseAge;
    }

    public ItemStack displayedShard() {
        return shard;
    }

    public boolean isRitualRunning() {
        return phase != Phase.IDLE;
    }

    public boolean isCircleMember(BlockPos resonatorPos) {
        for (BlockPos corner : CORNERS) {
            if (worldPosition.offset(corner).equals(resonatorPos)) {
                return true;
            }
        }
        return false;
    }

    /** 25 amethyst floor blocks one level down, resonators on the four corners. */
    public boolean isStructureValid() {
        if (level == null) {
            return false;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!level.getBlockState(worldPosition.offset(dx, -1, dz)).is(Blocks.AMETHYST_BLOCK)) {
                    return false;
                }
            }
        }
        for (BlockPos corner : CORNERS) {
            if (!level.getBlockState(worldPosition.offset(corner)).is(ModRegistry.RESONATOR.get())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Placing a shard commits the ritual. Returns false only when the machine
     * itself is not built (no shard is taken then) — every in-ritual problem
     * is a red FAILURE that still burns the shard.
     */
    public boolean startRitual(ItemStack shardStack) {
        if (level == null || level.isClientSide || phase != Phase.IDLE || !shardStack.is(ModRegistry.QUANTUM_SHARD.get())) {
            return false;
        }
        if (!isStructureValid()) {
            return false;
        }
        shard = shardStack.split(1);
        phase = Phase.CHARGING;
        phaseAge = 0;
        setChanged();
        level.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0f, 0.8f);
        return true;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, QuantumCoreBlockEntity core) {
        if (core.phase == Phase.IDLE) {
            return;
        }
        if (level.isClientSide) {
            // The client advances the phase clock itself between sync packets
            // (each phase transition resyncs it) — this is what drives the
            // shard spin and any renderer animation.
            core.phaseAge++;
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        core.phaseAge++;
        core.emitTheater(serverLevel);
        switch (core.phase) {
            case CHARGING -> {
                if (core.phaseAge % 8 == 0) {
                    float pitch = 0.8f + 0.6f * core.phaseAge / CHARGING_TICKS;
                    serverLevel.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, pitch);
                }
                if (core.phaseAge >= CHARGING_TICKS) {
                    core.enterPhase(Phase.JUDGEMENT);
                }
            }
            case JUDGEMENT -> {
                if (core.phaseAge >= JUDGEMENT_TICKS) {
                    boolean success = core.performRitual(serverLevel);
                    core.shard = ItemStack.EMPTY; // burned on both outcomes: the rules are taught, not refunded
                    if (success) {
                        core.enterPhase(Phase.SUCCESS);
                        serverLevel.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.4f);
                        serverLevel.playSound(null, pos, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 0.5f, 1.6f);
                        serverLevel.sendParticles(ParticleTypes.FLASH, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                                1, 0, 0, 0, 0);
                    } else {
                        core.enterPhase(Phase.FAILURE);
                        serverLevel.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f);
                    }
                }
            }
            case SUCCESS -> {
                if (core.phaseAge >= SUCCESS_TICKS) {
                    core.enterPhase(Phase.IDLE);
                }
            }
            case FAILURE -> {
                if (core.phaseAge >= FAILURE_TICKS) {
                    core.enterPhase(Phase.IDLE);
                }
            }
            default -> {
            }
        }
    }

    private void enterPhase(Phase next) {
        phase = next;
        phaseAge = 0;
        setChanged();
    }

    /** Particle streams resonators → above the core; colors follow the phase script. */
    private void emitTheater(ServerLevel level) {
        Vec3 focus = Vec3.atCenterOf(worldPosition).add(0, 1.1, 0);
        for (BlockPos corner : CORNERS) {
            BlockPos resonatorPos = worldPosition.offset(corner);
            boolean occupied = level.getBlockEntity(resonatorPos) instanceof ResonatorBlockEntity resonator
                    && !resonator.isEmpty();
            Vec3 from = Vec3.atCenterOf(resonatorPos).add(0, 0.8, 0);
            for (int sample = 0; sample < 2; sample++) {
                Vec3 point = from.lerp(focus, level.random.nextDouble());
                switch (phase) {
                    case CHARGING -> level.sendParticles(new DustParticleOptions(COLOR_CHARGE, 1.0f),
                            point.x, point.y, point.z, 1, 0.05, 0.05, 0.05, 0);
                    case JUDGEMENT -> level.sendParticles(
                            new DustParticleOptions(occupied ? COLOR_INPUT : COLOR_CHARGE, 1.0f),
                            point.x, point.y, point.z, 2, 0.05, 0.05, 0.05, 0);
                    case SUCCESS -> level.sendParticles(ParticleTypes.END_ROD,
                            point.x, point.y, point.z, 1, 0.1, 0.1, 0.1, 0.02);
                    case FAILURE -> {
                        level.sendParticles(new DustParticleOptions(COLOR_FAIL, 1.2f),
                                point.x, point.y, point.z, 2, 0.08, 0.08, 0.08, 0);
                        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                                focus.x, focus.y, focus.z, 1, 0.1, 0.1, 0.1, 0.01);
                    }
                    default -> {
                    }
                }
            }
        }
    }

    @Nullable
    private ResonatorBlockEntity resonatorAt(BlockPos corner) {
        if (level != null && level.getBlockEntity(worldPosition.offset(corner)) instanceof ResonatorBlockEntity resonator) {
            return resonator;
        }
        return null;
    }

    /**
     * The single-tick verdict + application. Every failure path returns false
     * without touching any stack; the success paths mutate resonator contents
     * only through whole-instance setItem (the engine adopts the canonicals).
     */
    private boolean performRitual(ServerLevel level) {
        if (!isStructureValid()) {
            return false; // someone mined the machine mid-ritual
        }
        List<ResonatorBlockEntity> circle = new ArrayList<>(4);
        for (BlockPos corner : CORNERS) {
            ResonatorBlockEntity resonator = resonatorAt(corner);
            if (resonator == null) {
                return false;
            }
            circle.add(resonator);
        }
        QuantumEngine engine = QuantumEngine.onServerThread();
        QuantumNetworks networks = QuantumNetworks.get(level.getServer());

        List<ResonatorBlockEntity> occupied = new ArrayList<>(4);
        List<ResonatorBlockEntity> vacant = new ArrayList<>(4);
        for (ResonatorBlockEntity resonator : circle) {
            if (resonator.isEmpty()) {
                vacant.add(resonator);
            } else {
                occupied.add(resonator);
            }
        }
        if (occupied.isEmpty() || vacant.isEmpty()) {
            return false;
        }

        List<ResonatorBlockEntity> linked = occupied.stream()
                .filter(r -> r.getItem(0).has(ModRegistry.QUANTUM_LINK.get()))
                .toList();

        if (linked.isEmpty()) {
            // Fresh entanglement: exactly one plain, stackable, undamageable input.
            if (occupied.size() != 1) {
                return false;
            }
            ResonatorBlockEntity inputResonator = occupied.get(0);
            ItemStack input = inputResonator.getItem(0);
            if (input.getMaxStackSize() <= 1 || input.isDamageableItem()) {
                return false;
            }
            int networkId = networks.createNetwork(input);
            ItemStack windowA = input.copy();
            windowA.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(networkId, 1));
            ItemStack windowB = input.copy();
            windowB.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(networkId, 2));
            inputResonator.setItem(0, windowA);
            vacant.get(0).setItem(0, windowB);
            if (engine != null) {
                engine.adopt(windowA);
                engine.adopt(windowB);
                engine.trackHolder(windowA, inputResonator);
                engine.trackHolder(windowB, vacant.get(0));
            }
            QuantumDebug.log(level.getServer(), "ritual created net#" + networkId + " "
                    + input.getItem() + " x" + input.getCount() + " members[1, 2]");
            return true;
        }

        // Expansion: EVERY live window of the network must be on the table —
        // recoherence is the price of growth, and it makes the member addition
        // fully local (all canonical instances sit in loaded BEs right here).
        if (linked.size() != occupied.size()) {
            return false; // plain strays mixed in
        }
        QuantumLinkData first = linked.get(0).getItem(0).get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks.Network network = networks.network(first.networkId());
        if (network == null || engine == null) {
            return false;
        }
        Set<Integer> presentMembers = new HashSet<>();
        for (ResonatorBlockEntity resonator : linked) {
            ItemStack window = resonator.getItem(0);
            QuantumLinkData link = window.get(ModRegistry.QUANTUM_LINK.get());
            if (link.networkId() != first.networkId() || !presentMembers.add(link.memberId())) {
                return false; // foreign network or duplicated member
            }
            if (engine.reconcile(window) != QuantumEngine.Status.CANONICAL) {
                return false;
            }
        }
        if (!presentMembers.equals(network.aliveMembers)) {
            return false; // some window of this network is elsewhere in the world
        }
        int member = networks.addMember(first.networkId());
        if (member < 0) {
            return false;
        }
        ItemStack newWindow = linked.get(0).getItem(0).copy();
        newWindow.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(first.networkId(), member));
        ResonatorBlockEntity target = vacant.get(0);
        target.setItem(0, newWindow);
        engine.adopt(newWindow);
        engine.trackHolder(newWindow, target);
        QuantumDebug.log(level.getServer(), "ritual expanded net#" + first.networkId() + " +member " + member);
        return true;
    }

    // --- persistence + client sync (phase drives future renderer animation) ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("phase", phase.ordinal());
        tag.putInt("phaseAge", phaseAge);
        if (!shard.isEmpty()) {
            tag.put("shard", shard.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        phase = Phase.byOrdinal(tag.getInt("phase"));
        phaseAge = tag.getInt("phaseAge");
        shard = tag.contains("shard") ? ItemStack.parseOptional(registries, tag.getCompound("shard")) : ItemStack.EMPTY;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        sendData();
    }
}
