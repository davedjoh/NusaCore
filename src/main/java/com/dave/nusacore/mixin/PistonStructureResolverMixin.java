package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Stops a piston from moving or destroying blocks across a claim border.
 *
 * <p>Vanilla resolves the whole movement before it touches the world, and that resolved plan is exactly what the
 * check needs. Returning {@code false} from {@code resolve} makes the piston behave as if it were blocked, so no
 * block is moved and nothing is dropped. A piston inside one claim may still move blocks within that claim or out of
 * it; a piston outside a claim can never push into one.
 */
@Mixin(PistonStructureResolver.class)
abstract class PistonStructureResolverMixin {
    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private BlockPos pistonPos;

    @Shadow
    @Final
    private List<BlockPos> toPush;

    @Shadow
    @Final
    private List<BlockPos> toDestroy;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void nusacore$protectPiston(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()) {
            return;
        }
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection != null && !protection.canPiston(level, pistonPos, toPush, toDestroy)) {
            callback.setReturnValue(false);
        }
    }
}
