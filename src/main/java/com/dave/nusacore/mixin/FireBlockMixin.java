package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops fire from consuming blocks inside a claim.
 *
 * <p>{@code FireBlock.checkBurnOut} is private and is where fire replaces a flammable block or primes TNT. Cancelling
 * it at the head leaves the fire block itself alone, so the flame still renders and ages, but nothing in the claim is
 * destroyed or primed. Fire spreading into a claim from outside is stopped by the same check on the destination.
 */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void nusacore$protectBurnOut(Level level, BlockPos pos, int chance, RandomSource random, int age,
            CallbackInfo callback) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection != null && !protection.canFire(level, pos)) {
            callback.cancel();
        }
    }
}
