package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops fluid from flowing into a claim from outside it.
 *
 * <p>{@code FlowingFluid.spreadTo} is protected and is the one place every spread, including the downward and
 * side-spread paths, reaches. The source position is the neighbour opposite the flow direction, so the check can
 * allow water that is already inside the same claim while denying water that would cross a border.
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void nusacore$protectSpread(LevelAccessor levelAccessor, BlockPos target, BlockState targetState,
            Direction direction, FluidState fluidState, CallbackInfo callback) {
        if (!(levelAccessor instanceof Level level)) {
            return;
        }
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return;
        }
        BlockPos source = target.relative(direction.getOpposite());
        if (!protection.canFluid(level, source, target)) {
            callback.cancel();
        }
    }
}
