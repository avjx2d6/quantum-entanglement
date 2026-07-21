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
 * resonators on its corners, the two-tall core in the center.
 *
 * The ritual is a strict commitment: lay everything out first, then place a
 * shard — it launches immediately and burns the shard whether it succeeds or
 * fails. From launch to the end every stack in the circle is locked.
 *
 * Staging (author's script): beams CONNECT to the resonators one by one →
 * the occupied (input) beams recolor one by one under a steady hum → the
 * verdict: the planned OUTPUT beam turns gold, or a loud CANCEL kills the
 * ritual → on success a rising Shepard tone builds, the core's glow steps
 * up, the shard whips around → the beams BURST with a crack and it all goes
 * dark. The entanglement math itself still happens in ONE tick (at the
 * burst); the dry-run at the verdict cannot diverge because the circle is
 * locked in between.
 */
public class QuantumCoreBlockEntity extends SyncedBlockEntity {
    /** Corner offsets of the circle, in deterministic order (output windows fill in this order). */
    private static final BlockPos[] CORNERS = {
            new BlockPos(-2, 0, -2), new BlockPos(2, 0, -2),
            new BlockPos(-2, 0, 2), new BlockPos(2, 0, 2)};

    public enum Phase {
        IDLE, CONNECTING, SCANNING, JUDGEMENT, CRESCENDO, SUCCESS, FAILURE;

        static Phase byOrdinal(int ordinal) {
            Phase[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
        }
    }

    public static final int CONNECTING_TICKS = 60;   // one beam every 15 ticks
    public static final int SCANNING_TICKS = 60;     // inputs recolor every 15 ticks
    public static final int JUDGEMENT_TICKS = 25;    // gold output beam shows
    public static final int CRESCENDO_TICKS = 180;   // engine spool-up, glow, spin (author: x3)
    public static final int SUCCESS_TICKS = 25;
    public static final int FAILURE_TICKS = 30;
    private static final int BEAM_STEP_TICKS = 15;

    /** Tick (from launch) at which a doomed ritual cancels. */
    public static int ticksUntilCancel() {
        return CONNECTING_TICKS + SCANNING_TICKS;
    }

    /** Tick (from launch) at which a successful ritual applies (the burst). */
    public static int ticksUntilApply() {
        return CONNECTING_TICKS + SCANNING_TICKS + JUDGEMENT_TICKS + CRESCENDO_TICKS;
    }

    /** Cumulative age across the scripted phases — drives the hum cadence and renderers. */
    public static int phaseOffset(Phase phase) {
        return switch (phase) {
            case SCANNING -> CONNECTING_TICKS;
            case JUDGEMENT -> CONNECTING_TICKS + SCANNING_TICKS;
            case CRESCENDO -> CONNECTING_TICKS + SCANNING_TICKS + JUDGEMENT_TICKS;
            default -> 0;
        };
    }

    private static final Vector3f COLOR_CHARGE = new Vector3f(0.75f, 0.55f, 1.0f);
    private static final Vector3f COLOR_INPUT = new Vector3f(0.35f, 0.9f, 1.0f);
    private static final Vector3f COLOR_OUTPUT = new Vector3f(1.0f, 0.84f, 0.3f);
    private static final Vector3f COLOR_FAIL = new Vector3f(1.0f, 0.2f, 0.15f);

    private Phase phase = Phase.IDLE;
    private int phaseAge;
    private ItemStack shard = ItemStack.EMPTY;
    /** Corner index (0..3) the dry-run picked for the new window; -1 = none. */
    private int plannedOutputCorner = -1;
    /** Who lit this ritual (for advancements); resolved at the outcome. */
    @Nullable
    private java.util.UUID launcherId;
    /** Members in the network after the last successful apply (for the quartet advancement). */
    private int lastRitualMemberCount;

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

    /** 25 amethyst floor blocks one level down, resonators on the four corners, own upper half intact. */
    public boolean isStructureValid() {
        if (level == null) {
            return false;
        }
        BlockState upper = level.getBlockState(worldPosition.above());
        if (!upper.is(ModRegistry.QUANTUM_CORE.get())
                || upper.getValue(QuantumCoreBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
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
        // No foreign blocks inside the circle: the two layers above the floor
        // must be air everywhere except the machine's own blocks (author's
        // request — the ritual space stays clean, beams never cross walls).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (isMachineBlock(dx, dy, dz)) {
                        continue;
                    }
                    if (!level.getBlockState(worldPosition.offset(dx, dy, dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isMachineBlock(int dx, int dy, int dz) {
        if (dx == 0 && dz == 0) {
            return true; // both core halves
        }
        return dy == 0 && Math.abs(dx) == 2 && Math.abs(dz) == 2; // resonators
    }

    /**
     * Placing a shard on a COMPLETE circle commits the ritual immediately;
     * every in-ritual problem is a red FAILURE that still burns the shard.
     * On an incomplete circle the shard simply lies on the core, inert and
     * retrievable with an empty hand.
     */
    public boolean placeShard(ItemStack shardStack) {
        return placeShard(shardStack, null);
    }

    public boolean placeShard(ItemStack shardStack, @Nullable net.minecraft.world.entity.player.Player igniter) {
        if (level == null || level.isClientSide || phase != Phase.IDLE || !shard.isEmpty()
                || !shardStack.is(ModRegistry.QUANTUM_SHARD.get())) {
            return false;
        }
        shard = shardStack.split(1);
        if (isStructureValid()) {
            phase = Phase.CONNECTING;
            phaseAge = 0;
            plannedOutputCorner = -1;
            launcherId = igniter != null ? igniter.getUUID() : null;
            level.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0f, 0.8f);
        } else {
            level.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS, 0.7f, 1.2f);
        }
        setChanged();
        return true;
    }

    /** Empty-hand pickup of an inert (idle) shard; a committed ritual never gives it back. */
    public ItemStack takeShard() {
        if (level == null || level.isClientSide || phase != Phase.IDLE || shard.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = shard;
        shard = ItemStack.EMPTY;
        setChanged();
        return result;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, QuantumCoreBlockEntity core) {
        boolean activePhases = core.phase == Phase.CONNECTING || core.phase == Phase.SCANNING
                || core.phase == Phase.JUDGEMENT || core.phase == Phase.CRESCENDO;
        com.quantumitems.engine.ActiveRitualCores.report(level, pos, activePhases);
        if (core.phase == Phase.IDLE) {
            return;
        }
        if (level.isClientSide) {
            // The client advances the phase clock itself between sync packets
            // (each phase transition resyncs it) — this drives the renderers.
            core.phaseAge++;
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        core.phaseAge++;
        core.emitTheater(serverLevel);
        if (activePhases) {
            core.drainExperience(serverLevel);
            core.pullExperienceOrbs(serverLevel);
        }
        switch (core.phase) {
            case CONNECTING -> {
                if (core.phaseAge % BEAM_STEP_TICKS == 1 && core.phaseAge / BEAM_STEP_TICKS < 4) {
                    int beam = core.phaseAge / BEAM_STEP_TICKS;
                    serverLevel.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS,
                            1.0f, 0.55f + 0.1f * beam);
                }
                if (core.phaseAge >= CONNECTING_TICKS) {
                    core.enterPhase(Phase.SCANNING);
                }
            }
            case SCANNING -> {
                if (core.phaseAge % BEAM_STEP_TICKS == 1) {
                    serverLevel.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.9f, 1.3f);
                }
                if (core.phaseAge >= SCANNING_TICKS) {
                    core.plannedOutputCorner = core.performRitual(serverLevel, false);
                    if (core.plannedOutputCorner < 0) {
                        core.cancelRitual(serverLevel);
                    } else {
                        core.enterPhase(Phase.JUDGEMENT);
                        serverLevel.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE,
                                SoundSource.BLOCKS, 1.0f, 1.5f);
                    }
                }
            }
            case JUDGEMENT -> {
                if (core.phaseAge >= JUDGEMENT_TICKS) {
                    core.enterPhase(Phase.CRESCENDO);
                    serverLevel.playSound(null, pos, ModRegistry.RITUAL_RISER.get(), SoundSource.BLOCKS, 1.2f, 1.0f);
                }
            }
            case CRESCENDO -> {
                // Smooth light ramp: 0 → 15 across the crescendo, one level at a
                // time (setGlow no-ops when the level is unchanged, so this is at
                // most 15 block updates over the whole phase).
                int targetGlow = Math.min(15, (int) ((long) core.phaseAge * 15 / CRESCENDO_TICKS));
                core.setGlow(serverLevel, targetGlow);
                if (core.phaseAge >= CRESCENDO_TICKS) {
                    int result = core.performRitual(serverLevel, true);
                    core.shard = ItemStack.EMPTY; // burned: the rules are taught, not refunded
                    core.setGlow(serverLevel, 0);
                    core.releaseClaimedOrbs(serverLevel);
                    if (result >= 0) {
                        core.burst(serverLevel);
                        core.enterPhase(Phase.SUCCESS);
                        core.awardSuccess(serverLevel);
                    } else {
                        // the locked circle should make this impossible; fail honestly if it happens
                        serverLevel.playSound(null, pos, ModRegistry.RITUAL_CANCEL.get(), SoundSource.BLOCKS, 2.5f, 0.95f);
                        core.enterPhase(Phase.FAILURE);
                        core.awardFailure(serverLevel);
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

    /** The loud "отмена": burn the shard, kill the hum, go red. */
    private void cancelRitual(ServerLevel level) {
        shard = ItemStack.EMPTY;
        setGlow(level, 0);
        releaseClaimedOrbs(level);
        level.playSound(null, worldPosition, ModRegistry.RITUAL_CANCEL.get(), SoundSource.BLOCKS, 2.5f, 0.95f);
        enterPhase(Phase.FAILURE);
        awardFailure(level);
    }

    @Nullable
    private net.minecraft.server.level.ServerPlayer launcher(ServerLevel level) {
        return launcherId != null ? level.getServer().getPlayerList().getPlayer(launcherId) : null;
    }

    private void awardSuccess(ServerLevel level) {
        net.minecraft.server.level.ServerPlayer player = launcher(level);
        if (player != null) {
            com.quantumitems.QuantumAdvancements.award(player, com.quantumitems.QuantumAdvancements.ENTANGLED);
            if (lastRitualMemberCount >= QuantumNetworks.MAX_MEMBERS) {
                com.quantumitems.QuantumAdvancements.award(player, com.quantumitems.QuantumAdvancements.QUARTET);
            }
        }
        launcherId = null;
    }

    private void awardFailure(ServerLevel level) {
        net.minecraft.server.level.ServerPlayer player = launcher(level);
        if (player != null) {
            com.quantumitems.QuantumAdvancements.award(player, com.quantumitems.QuantumAdvancements.YOUR_OWN_FAULT);
        }
        launcherId = null;
    }

    /** Beam lines explode outward, one sharp crack, then darkness. */
    private void burst(ServerLevel level) {
        Vec3 focus = beamFocus();
        for (BlockPos corner : CORNERS) {
            Vec3 from = Vec3.atCenterOf(worldPosition.offset(corner)).add(0, 0.8, 0);
            for (int i = 0; i <= 12; i++) {
                Vec3 point = from.lerp(focus, i / 12.0);
                level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 3, 0.15, 0.15, 0.15, 0.25);
                level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.1, 0.1, 0.1, 0.08);
            }
        }
        level.sendParticles(ParticleTypes.FLASH, focus.x, focus.y, focus.z, 1, 0, 0, 0, 0);
        level.playSound(null, worldPosition, ModRegistry.RITUAL_BURST.get(), SoundSource.BLOCKS, 2.5f, 1.0f);
    }

    private void enterPhase(Phase next) {
        phase = next;
        phaseAge = 0;
        setChanged();
    }

    /** Light emission for the crescendo ramp (GLOW blockstate 0..15 on both halves). */
    private void setGlow(ServerLevel level, int glow) {
        for (BlockPos pos : new BlockPos[]{worldPosition, worldPosition.above()}) {
            BlockState state = level.getBlockState(pos);
            if (state.is(ModRegistry.QUANTUM_CORE.get()) && state.getValue(QuantumCoreBlock.GLOW) != glow) {
                level.setBlock(pos, state.setValue(QuantumCoreBlock.GLOW, glow), 3);
            }
        }
    }

    private Vec3 beamFocus() {
        return Vec3.atCenterOf(worldPosition).add(0, 0.95, 0); // the shard inside the upper frame
    }

    /**
     * Beam script (author's storyboard): beams connect one by one, inputs
     * recolor one by one, the planned output goes gold, the crescendo
     * doubles the density, failure bleeds red.
     */
    private void emitTheater(ServerLevel level) {
        Vec3 focus = beamFocus();
        int connectedBeams = switch (phase) {
            case CONNECTING -> Math.min(4, 1 + phaseAge / BEAM_STEP_TICKS);
            case SCANNING, JUDGEMENT, CRESCENDO -> 4;
            default -> 0;
        };
        int recoloredInputs = switch (phase) {
            case SCANNING -> 1 + phaseAge / BEAM_STEP_TICKS;
            case JUDGEMENT, CRESCENDO -> 4;
            default -> 0;
        };
        int density = phase == Phase.CRESCENDO ? 4 : 2;
        int inputSeen = 0;
        for (int i = 0; i < CORNERS.length; i++) {
            BlockPos resonatorPos = worldPosition.offset(CORNERS[i]);
            boolean occupied = level.getBlockEntity(resonatorPos) instanceof ResonatorBlockEntity resonator
                    && !resonator.isEmpty();
            Vec3 from = Vec3.atCenterOf(resonatorPos).add(0, 0.8, 0);

            if (phase == Phase.SUCCESS) {
                level.sendParticles(ParticleTypes.END_ROD,
                        from.x, from.y + 0.3, from.z, 1, 0.15, 0.2, 0.15, 0.01);
                continue;
            }
            if (phase == Phase.FAILURE) {
                Vec3 point = from.lerp(focus, level.random.nextDouble());
                level.sendParticles(new DustParticleOptions(COLOR_FAIL, 1.2f),
                        point.x, point.y, point.z, 2, 0.08, 0.08, 0.08, 0);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        focus.x, focus.y, focus.z, 1, 0.1, 0.1, 0.1, 0.01);
                continue;
            }
            if (i >= connectedBeams) {
                continue;
            }
            Vector3f color = COLOR_CHARGE;
            if (occupied) {
                inputSeen++;
                if (inputSeen <= recoloredInputs) {
                    color = COLOR_INPUT;
                }
            }
            if ((phase == Phase.JUDGEMENT || phase == Phase.CRESCENDO) && i == plannedOutputCorner) {
                color = COLOR_OUTPUT;
            }
            for (int sample = 0; sample < density; sample++) {
                Vec3 point = from.lerp(focus, level.random.nextDouble());
                level.sendParticles(new DustParticleOptions(color, 1.0f),
                        point.x, point.y, point.z, 1, 0.05, 0.05, 0.05, 0);
            }
        }
    }

    // --- experience drain: the Observer drinks what you have seen ---

    private static final double XP_RADIUS = 7.0;
    /** Mirrored in ExperienceOrbMixin — a claimed orb cannot be picked up. */
    private static final String CLAIMED_TAG = "quantumitems_claimed";

    private Vec3 observerEyePos() {
        return Vec3.atCenterOf(worldPosition).add(0, 0.05, 0);
    }

    /**
     * While the ritual runs, nearby players leak experience: a point at a
     * time detaches as a REAL orb that the core then reels in. The closer
     * you stand, the faster it bleeds. Creative and spectator players are
     * exempt. Claimed orbs cannot be picked back up (author's ruling).
     */
    private void drainExperience(ServerLevel level) {
        Vec3 eye = observerEyePos();
        var box = new net.minecraft.world.phys.AABB(worldPosition).inflate(XP_RADIUS);
        for (net.minecraft.world.entity.player.Player player
                : level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box)) {
            if (player.isCreative() || player.isSpectator()) {
                continue;
            }
            double dist = player.position().distanceTo(eye);
            if (dist > XP_RADIUS) {
                continue;
            }
            int interval = dist < 2.5 ? 6 : dist < 5.0 ? 12 : 20;
            if (phaseAge % interval != 0) {
                continue;
            }
            if (player.experienceLevel <= 0 && player.experienceProgress <= 0.0f) {
                continue; // nothing left to drink
            }
            player.giveExperiencePoints(-1);
            // The orb leaves FROM the player (mid-body), not from a point
            // toward the core — spawning it offset put it inside the absorb
            // zone when the player stood close, so it blinked out unseen.
            var orb = new net.minecraft.world.entity.ExperienceOrb(level,
                    player.getX(), player.getY() + player.getBbHeight() * 0.6, player.getZ(), 1);
            orb.addTag(CLAIMED_TAG); // claimed BEFORE entering the world: never insta-picked
            level.addFreshEntity(orb);
        }
    }

    /**
     * ALL experience orbs in radius — leaked, mob-dropped, thrown bottles —
     * drift slowly toward the Observer and vanish into it. Claimed orbs are
     * un-pickable and their vanilla player attraction is switched off (see
     * ExperienceOrbMixin); the claim self-heals if the core stops re-tagging.
     */
    private void pullExperienceOrbs(ServerLevel level) {
        Vec3 eye = observerEyePos();
        // Absorb on TOUCHING the two-block core column (slightly inflated),
        // not at a point-distance from the eye: an orb resting on TOP of the
        // core — a player standing on it feeds from up there — touches the
        // column and is eaten, instead of hovering forever out of point range.
        var column = new net.minecraft.world.phys.AABB(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                worldPosition.getX() + 1, worldPosition.getY() + 2, worldPosition.getZ() + 1)
                .inflate(0.3);
        var box = new net.minecraft.world.phys.AABB(worldPosition).inflate(XP_RADIUS);
        for (net.minecraft.world.entity.ExperienceOrb orb
                : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)) {
            // A freshly drained orb gets a moment of visible flight before it
            // can be eaten — spawn-and-vanish reads as a glitch.
            if (orb.tickCount > 8 && column.contains(orb.position())) {
                level.sendParticles(ParticleTypes.PORTAL, eye.x, eye.y, eye.z, 6, 0.1, 0.1, 0.1, 0.05);
                level.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_CHIME,
                        SoundSource.BLOCKS, 0.4f, 1.8f);
                orb.discard();
                continue;
            }
            orb.addTag(CLAIMED_TAG); // pickup block; the ORB's own tick steers it (both sides)
        }
    }

    /** Ritual over: leftover claimed orbs become ordinary orbs again. */
    private void releaseClaimedOrbs(ServerLevel level) {
        var box = new net.minecraft.world.phys.AABB(worldPosition).inflate(XP_RADIUS + 2);
        for (net.minecraft.world.entity.ExperienceOrb orb
                : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)) {
            orb.removeTag(CLAIMED_TAG);
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
     * Verdict + (optionally) application in one pass. Returns the corner
     * index (0..3) chosen for the output window, or -1 on failure. The dry
     * run at the verdict and the applying run at the burst cannot diverge:
     * the circle is locked in between. Failure paths never touch a stack;
     * the success path mutates resonator contents only through
     * whole-instance setItem (the engine adopts the canonicals).
     */
    private int performRitual(ServerLevel level, boolean apply) {
        if (!isStructureValid()) {
            return -1; // someone mined the machine mid-ritual
        }
        List<ResonatorBlockEntity> circle = new ArrayList<>(4);
        for (BlockPos corner : CORNERS) {
            ResonatorBlockEntity resonator = resonatorAt(corner);
            if (resonator == null) {
                return -1;
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
            return -1;
        }
        int outputCorner = circle.indexOf(vacant.get(0));

        List<ResonatorBlockEntity> linked = occupied.stream()
                .filter(r -> r.getItem(0).has(ModRegistry.QUANTUM_LINK.get()))
                .toList();

        if (linked.isEmpty()) {
            // Fresh entanglement: exactly one plain, stackable, undamageable input.
            if (occupied.size() != 1) {
                return -1;
            }
            ResonatorBlockEntity inputResonator = occupied.get(0);
            ItemStack input = inputResonator.getItem(0);
            if (input.getMaxStackSize() <= 1 || input.isDamageableItem()) {
                return -1;
            }
            if (!apply) {
                return outputCorner;
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
            lastRitualMemberCount = 2;
            return outputCorner;
        }

        // Expansion: EVERY live window of the network must be on the table —
        // recoherence is the price of growth, and it makes the member addition
        // fully local (all canonical instances sit in loaded BEs right here).
        if (linked.size() != occupied.size()) {
            return -1; // plain strays mixed in
        }
        QuantumLinkData first = linked.get(0).getItem(0).get(ModRegistry.QUANTUM_LINK.get());
        QuantumNetworks.Network network = networks.network(first.networkId());
        if (network == null || engine == null) {
            return -1;
        }
        Set<Integer> presentMembers = new HashSet<>();
        for (ResonatorBlockEntity resonator : linked) {
            ItemStack window = resonator.getItem(0);
            QuantumLinkData link = window.get(ModRegistry.QUANTUM_LINK.get());
            if (link.networkId() != first.networkId() || !presentMembers.add(link.memberId())) {
                return -1; // foreign network or duplicated member
            }
            if (engine.reconcile(window) != QuantumEngine.Status.CANONICAL) {
                return -1;
            }
        }
        if (!presentMembers.equals(network.aliveMembers)) {
            return -1; // some window of this network is elsewhere in the world
        }
        if (network.aliveMembers.size() >= QuantumNetworks.MAX_MEMBERS) {
            return -1;
        }
        if (!apply) {
            return outputCorner;
        }
        int member = networks.addMember(first.networkId());
        if (member < 0) {
            return -1;
        }
        ItemStack newWindow = linked.get(0).getItem(0).copy();
        newWindow.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(first.networkId(), member));
        ResonatorBlockEntity target = vacant.get(0);
        target.setItem(0, newWindow);
        engine.adopt(newWindow);
        engine.trackHolder(newWindow, target);
        QuantumDebug.log(level.getServer(), "ritual expanded net#" + first.networkId() + " +member " + member);
        lastRitualMemberCount = network.aliveMembers.size();
        return outputCorner;
    }

    // --- persistence + client sync ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("phase", phase.ordinal());
        tag.putInt("phaseAge", phaseAge);
        tag.putInt("plannedOutput", plannedOutputCorner);
        if (!shard.isEmpty()) {
            tag.put("shard", shard.save(registries));
        }
        tag.putInt("lastMembers", lastRitualMemberCount);
        if (launcherId != null) {
            tag.putUUID("launcher", launcherId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        phase = Phase.byOrdinal(tag.getInt("phase"));
        phaseAge = tag.getInt("phaseAge");
        plannedOutputCorner = tag.contains("plannedOutput") ? tag.getInt("plannedOutput") : -1;
        shard = tag.contains("shard") ? ItemStack.parseOptional(registries, tag.getCompound("shard")) : ItemStack.EMPTY;
        lastRitualMemberCount = tag.getInt("lastMembers");
        launcherId = tag.hasUUID("launcher") ? tag.getUUID("launcher") : null;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        sendData();
    }

    @Override
    public void setRemoved() {
        if (level != null) {
            com.quantumitems.engine.ActiveRitualCores.report(level, worldPosition, false);
        }
        super.setRemoved();
    }
}
