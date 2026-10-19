package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a dispenser from firing into a claim it is not part of.
 *
 * <p>A dispenser has no player actor, so the conservative rule is that its output may not cross a claim border. A
 * dispenser inside a claim may still fire within it or out of it.
 */
@Mixin(DispenserBlock.class)
abstract class DispenserBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void nusacore$protectDispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo callback) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return;
        }
        Direction facing = state.getValue(DispenserBlock.FACING);
        if (!protection.canDispense(level, pos, pos.relative(facing))) {
            callback.cancel();
        }
    }
}
