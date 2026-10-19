package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stops a hopper minecart from collecting items that belong to a claim it is not part of.
 *
 * <p>The minecart's own {@code suckInItems} also collects item entities directly after delegating to
 * {@link net.minecraft.world.level.block.entity.HopperBlockEntity}, so the block-hopper mixin alone would leave that
 * path open.
 */
@Mixin(MinecartHopper.class)
abstract class MinecartHopperMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private void nusacore$protectSuck(CallbackInfoReturnable<Boolean> callback) {
        MinecartHopper minecart = (MinecartHopper) (Object) this;
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return;
        }
        Level level = minecart.level();
        BlockPos minecartPos = minecart.blockPosition();
        List<BlockPos> sources = new ArrayList<>();
        sources.add(minecartPos);
        BlockPos above = minecartPos.above();
        if (level.getBlockEntity(above) instanceof Container) {
            sources.add(above);
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, minecart.getBoundingBox().inflate(0.25))) {
            sources.add(item.blockPosition());
        }
        if (!protection.canHopper(level, minecartPos, sources)) {
            callback.setReturnValue(false);
        }
    }
}
