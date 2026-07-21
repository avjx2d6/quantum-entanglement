package com.quantumitems.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Orbs claimed by a ritual core (tagged at spawn by the drain and re-tagged
 * every tick by the pull) are beyond a player's reach:
 * — no pickup: playerTouch is cancelled outright;
 * — no vanilla attraction: followingPlayer is nulled at the head of every
 *   orb tick, BEFORE vanilla's own movement logic runs — authoritative
 *   regardless of entity-vs-block-entity tick ordering (the bug: the orb
 *   re-acquired the player after the core's pull and walked home).
 * The claim self-heals: if no core re-tags for ~3 seconds, the tag drops
 * and the orb becomes an ordinary orb again.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    private static final String CLAIMED_TAG = "quantumitems_claimed";

    @Shadow
    @Nullable
    private Player followingPlayer;

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void quantumitems$noPickupWhileClaimed(Player player, CallbackInfo ci) {
        if (((Entity) (Object) this).getTags().contains(CLAIMED_TAG)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void quantumitems$claimedOrbsIgnorePlayers(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        boolean tagged = self.getTags().contains(CLAIMED_TAG);
        // The pull runs in the ORB's own tick on BOTH sides (vanilla syncs an
        // orb's position only every 20 ticks — a server-driven pull left the
        // client free-falling and rubber-banding between corrections). The
        // active-core tracker is populated by both sides' BE ticks, so client
        // and server integrate the exact same trajectory.
        net.minecraft.core.BlockPos core = com.quantumitems.engine.ActiveRitualCores
                .nearestActiveCore(self.level(), self.position());
        if (tagged || core != null) {
            this.followingPlayer = null;
        }
        if (core != null) {
            net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(
                    core.getX() + 0.5, core.getY() + 0.55, core.getZ() + 0.5);
            net.minecraft.world.phys.Vec3 toEye = eye.subtract(self.position());
            // Horizontal-only steering blended into the orb's own motion; the
            // vertical axis is left to vanilla gravity (author's call: orbs
            // FALL, arc down and slide along the floor toward the core, where
            // the column-touch absorb eats them at the base).
            net.minecraft.world.phys.Vec3 pull = new net.minecraft.world.phys.Vec3(toEye.x, 0, toEye.z);
            double dist = toEye.length();
            if (pull.lengthSqr() > 1e-6) {
                double strength = 0.05 + 0.09 * Math.max(0.0, 1.0 - dist / 8.5);
                pull = pull.normalize().scale(strength);
                net.minecraft.world.phys.Vec3 motion = self.getDeltaMovement();
                self.setDeltaMovement(motion.x * 0.6 + pull.x, motion.y, motion.z * 0.6 + pull.z);
            }
        }
        if (tagged && self.tickCount % 60 == 0) {
            self.removeTag(CLAIMED_TAG); // an active core re-tags every tick
        }
    }
}
