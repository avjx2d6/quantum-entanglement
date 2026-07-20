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
            double dist = toEye.length();
            if (dist > 1e-3) {
                double strength = 0.04 + 0.08 * Math.max(0.0, 1.0 - dist / 8.5);
                // +0.03 vertical counters the gravity vanilla adds later this tick
                self.setDeltaMovement(toEye.normalize().scale(strength).add(0, 0.03, 0));
            }
        }
        if (tagged && self.tickCount % 60 == 0) {
            self.removeTag(CLAIMED_TAG); // an active core re-tags every tick
        }
    }
}
