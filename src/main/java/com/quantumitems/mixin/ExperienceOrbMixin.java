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
        if (!self.getTags().contains(CLAIMED_TAG)) {
            return;
        }
        this.followingPlayer = null;
        if (self.tickCount % 60 == 0) {
            self.removeTag(CLAIMED_TAG); // an active core re-tags every tick
        }
    }
}
