package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Filters the block positions a vanilla explosion may destroy.
 *
 * <p>{@code ServerExplosion} has no callback that exposes its affected blocks, so this is one of the narrow gaps
 * that needs a mixin. Filtering at {@code interactWithBlocks} leaves entity damage, knockback, particles, and sound
 * to vanilla; only block destruction and fire creation are restrained. TNT, creepers, beds, respawn anchors, and
 * end crystals all pass through this class.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
    private List<BlockPos> nusacore$protectBlocks(List<BlockPos> positions) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return positions;
        }
        Entity source = ((Explosion) (Object) this).getIndirectSourceEntity();
        return positions.stream()
                .filter(pos -> protection.canExplode(level, pos, source))
                .toList();
    }

    @ModifyVariable(method = "createFire", at = @At("HEAD"), argsOnly = true)
    private List<BlockPos> nusacore$protectFire(List<BlockPos> positions) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return positions;
        }
        return positions.stream()
                .filter(pos -> protection.canFire(level, pos))
                .toList();
    }
}
