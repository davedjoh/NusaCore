package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a dropper from pushing items into a claim it is not part of.
 *
 * <p>{@link DropperBlock} overrides {@code dispenseFrom}, so the dispenser mixin does not cover it and this separate
 * injection is required.
 */
@Mixin(DropperBlock.class)
abstract class DropperBlockMixin {
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
