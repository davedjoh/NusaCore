package com.dave.nusacore.mixin;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.server.protection.ProtectionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stops a hopper from pulling items out of a claim it is not part of.
 *
 * <p>{@code HopperBlockEntity.suckInItems} is the shared entry point for block hoppers and for hopper minecarts that
 * delegate to it. The check covers the hopper itself, a container directly above it, and any item entities inside its
 * suction box. A hopper outside a claim cannot pull from inside one, and a hopper in one claim cannot pull from
 * another; a hopper inside a claim may still collect from unclaimed ground.
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void nusacore$protectSuck(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> callback) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return;
        }
        BlockPos hopperPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        List<BlockPos> sources = new ArrayList<>();
        sources.add(hopperPos);
        BlockPos above = hopperPos.above();
        if (level.getBlockEntity(above) instanceof Container) {
            sources.add(above);
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, hopper.getSuckAabb())) {
            sources.add(item.blockPosition());
        }
        if (!protection.canHopper(level, hopperPos, sources)) {
            callback.setReturnValue(false);
        }
    }
}
