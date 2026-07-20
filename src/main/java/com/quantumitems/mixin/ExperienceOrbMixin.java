package com.quantumitems.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Orbs claimed by a ritual core (tagged each tick by the pulling core) are
 * beyond reach: no player pickup, and the claim self-heals — if the core
 * stops re-tagging (ritual over, chunk weirdness), the tag drops within a
 * few seconds and the orb becomes an ordinary orb again. The core also
 * nulls {@code followingPlayer} through the accessor, so vanilla's own
 * player attraction never fights the pull.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    private static final String CLAIMED_TAG = "quantumitems_claimed";

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void quantumitems$noPickupWhileClaimed(Player player, CallbackInfo ci) {
        if (((Entity) (Object) this).getTags().contains(CLAIMED_TAG)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void quantumitems$selfHealClaim(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.tickCount % 60 == 0 && self.getTags().contains(CLAIMED_TAG)) {
            self.removeTag(CLAIMED_TAG); // an active core re-tags every tick
        }
    }

    @Mixin(ExperienceOrb.class)
    public interface FollowingAccessor {
        @Accessor("followingPlayer")
        void quantumitems$setFollowingPlayer(Player player);
    }
}
