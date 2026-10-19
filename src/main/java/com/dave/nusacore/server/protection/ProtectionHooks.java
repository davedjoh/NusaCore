package com.dave.nusacore.server.protection;

import com.dave.nusacore.core.claim.ClaimProtection;
import com.dave.nusacore.core.claim.ProtectionAction;
import com.dave.nusacore.core.permission.PermissionNodes;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The Fabric callbacks that enforce claim protection.
 *
 * <p>Callbacks cover the common player paths: breaking, attacking, placing, using an item on a block, opening a
 * container, and interacting with or attacking an entity. The remaining vanilla gaps that carry no usable callback
 * are handled by the mixins in {@code com.dave.nusacore.mixin}.
 *
 * <p>Every callback is silent on denial. Vanilla already plays the failed-action feedback, and a chat line per
 * blocked click would be noise. The callback returns the failing result, so the action never reaches the world.
 */
public final class ProtectionHooks {
    private ProtectionHooks() {
    }

    /** Registers every protection callback. Called once from the server entrypoint. */
    public static void install() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            ClaimProtection protection = ProtectionRuntime.get();
            if (protection == null || !(level instanceof ServerLevel serverLevel)
                    || !(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }
            return protection.canModify(serverPlayer, serverLevel, pos, ProtectionAction.BUILD);
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                checkBlock(player, level, pos, ProtectionAction.BUILD));

        UseBlockCallback.EVENT.register(ProtectionHooks::useBlock);

        UseItemCallback.EVENT.register((player, level, hand) -> useItem(player, level));

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                checkEntity(player, level, entity, true));

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                checkEntity(player, level, entity, false));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            ClaimProtection protection = ProtectionRuntime.get();
            if (protection == null || !(entity.level() instanceof ServerLevel level)) {
                return true;
            }
            return protection.canDamage(level, entity, source);
        });
    }

    private static InteractionResult useBlock(Player player, Level level, InteractionHand hand,
            BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = hitResult.getBlockPos();
        ProtectionAction clickedAction = isContainer(serverLevel, clicked)
                ? ProtectionAction.CONTAINERS
                : ProtectionAction.USE;
        if (!canInteract(serverPlayer, serverLevel, clicked, clickedAction)) {
            return InteractionResult.FAIL;
        }
        // Placing a block against a claimed block is a neighbour placement. Check the position the block would
        // occupy as well as the block it is attached to, so a claim border cannot be used as a free anchor.
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
            BlockPos placed = clicked.relative(hitResult.getDirection());
            if (!canInteract(serverPlayer, serverLevel, placed, ProtectionAction.BUILD)) {
                return InteractionResult.FAIL;
            }
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult useItem(Player player, Level level) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        HitResult hit = serverPlayer.pick(serverPlayer.blockInteractionRange(), 0.0f, false);
        if (hit instanceof BlockHitResult blockHit) {
            ProtectionAction action = isContainer(serverLevel, blockHit.getBlockPos())
                    ? ProtectionAction.CONTAINERS
                    : ProtectionAction.USE;
            if (!canInteract(serverPlayer, serverLevel, blockHit.getBlockPos(), action)) {
                return InteractionResult.FAIL;
            }
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult checkBlock(Player player, Level level, BlockPos pos, ProtectionAction action) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        return canInteract(serverPlayer, serverLevel, pos, action)
                ? InteractionResult.PASS
                : InteractionResult.FAIL;
    }

    private static InteractionResult checkEntity(Player player, Level level, Entity entity, boolean use) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        // This slice does not add a PvP ban. Player-versus-player damage and interaction stay vanilla.
        if (entity instanceof Player) {
            return InteractionResult.PASS;
        }
        ProtectionAction action;
        if (use) {
            action = entity instanceof AbstractMinecartContainer
                    ? ProtectionAction.CONTAINERS
                    : ProtectionAction.USE;
        } else {
            action = ProtectionAction.BUILD;
        }
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return InteractionResult.PASS;
        }
        return protection.canInteract(serverPlayer, serverLevel, entity.blockPosition(), action)
                ? InteractionResult.PASS
                : InteractionResult.FAIL;
    }

    private static boolean canInteract(ServerPlayer player, ServerLevel level, BlockPos pos, ProtectionAction action) {
        ClaimProtection protection = ProtectionRuntime.get();
        if (protection == null) {
            return true;
        }
        return protection.canInteract(player, level, pos, action);
    }

    private static boolean isContainer(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container;
    }

    /** The bypass permission as a predicate over an online player. */
    public static boolean bypasses(ServerPlayer player) {
        return PermissionNodes.check(player, PermissionNodes.CLAIM_BYPASS);
    }
}
