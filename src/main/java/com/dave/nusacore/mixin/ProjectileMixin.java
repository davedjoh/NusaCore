package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops a projectile from interacting with a claimed block when its owner has no access.
 *
 * <p>{@code Projectile.mayInteract} and {@code mayBreak} are the vanilla permission points used by arrows, tridents,
 * and dispensed projectiles. A projectile owned by a player is judged as that player; one with no player owner is
 * refused in claims, which also covers dispensers and skeletons.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
    @Shadow
    public abstract Entity getOwner();

    @Inject(method = "mayInteract", at = @At("HEAD"), cancellable = true)
    private void nusacore$protectInteract(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection != null && !protection.canProjectile(level, pos, getOwner())) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "mayBreak", at = @At("HEAD"), cancellable = true)
    private void nusacore$protectBreak(ServerLevel level, CallbackInfoReturnable<Boolean> callback) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return;
        }
        Projectile projectile = (Projectile) (Object) this;
        if (!protection.canProjectile(level, projectile.blockPosition(), getOwner())) {
            callback.setReturnValue(false);
        }
    }
}
