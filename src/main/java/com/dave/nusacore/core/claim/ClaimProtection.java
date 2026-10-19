package com.dave.nusacore.core.claim;

import com.dave.nusacore.core.cache.ClaimCache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The in-memory protection decision.
 *
 * <p>Every check is a chunk-key lookup in {@link ClaimCache}; no check reads storage, so it is safe on the tick
 * thread and on the many callbacks that run during a single interaction.
 *
 * <p>When storage is not ready the answer is deny, not allow. A cache that has not finished loading cannot tell an
 * unclaimed chunk from an unloaded one, and treating that as unclaimed would let the first player through the door
 * grief a claim that simply had not loaded yet. The cost is that building and explosions wait for the initial load,
 * which is the deliberate fail-closed trade.
 *
 * <p>Trusted players receive access only. Ownership, trust management, and unclaiming are checked by
 * {@link ClaimService} and never by this class.
 */
public final class ClaimProtection {
    private final ClaimCache cache;
    private final BooleanSupplier ready;
    private final Predicate<ServerPlayer> bypass;

    /**
     * @param cache the committed claim view
     * @param ready whether the initial load finished; false makes every check deny
     * @param bypass the protection-bypass permission, which never grants claim management
     */
    public ClaimProtection(ClaimCache cache, BooleanSupplier ready, Predicate<ServerPlayer> bypass) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.ready = Objects.requireNonNull(ready, "ready");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
    }

    /**
     * A protection instance that denies every check.
     *
     * <p>Used when storage could not be opened, and while a server is stopping, so a missing claim view is never
     * mistaken for an empty world.
     */
    public static ClaimProtection unavailable() {
        return new ClaimProtection(new ClaimCache(), () -> false, player -> false);
    }

    /** True when the claim view is loaded. */
    public boolean ready() {
        return ready.getAsBoolean();
    }

    /** A block break, place, attack, or entity attack by a player. */
    public boolean canModify(ServerPlayer player, Level level, BlockPos pos, ProtectionAction action) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(action, "action");
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim claim = claimAt(level, pos);
        if (claim == null) {
            return true;
        }
        return allows(claim, player, action);
    }

    /** A block interaction by a player, using the same rules as {@link #canModify}. */
    public boolean canInteract(ServerPlayer player, Level level, BlockPos pos, ProtectionAction action) {
        return canModify(player, level, pos, action);
    }

    /**
     * Whether an explosion may destroy the block at {@code pos}.
     *
     * <p>A player who caused the explosion, directly or through primed TNT or a projectile, is treated as the actor.
     * A creeper or a dispenser has no actor, so its explosion cannot destroy blocks in any claim.
     */
    public boolean canExplode(ServerLevel level, BlockPos pos, Entity source) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim claim = claimAt(level, pos);
        if (claim == null) {
            return true;
        }
        ServerPlayer actor = playerActor(source);
        return actor != null && allows(claim, actor, ProtectionAction.BUILD);
    }

    /**
     * Whether a piston may move or destroy the listed blocks.
     *
     * <p>A piston has no player actor. The conservative rule is that a claimed piston may only move blocks that are
     * unclaimed or belong to the same claim. A piston outside a claim can never push into one, and a piston in one
     * claim can never push into another.
     */
    public boolean canPiston(Level level, BlockPos pistonPos, List<BlockPos> toPush, List<BlockPos> toDestroy) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim pistonClaim = claimAt(level, pistonPos);
        for (BlockPos pos : toPush) {
            if (!sameClaimOrOutside(level, pistonClaim, pos)) {
                return false;
            }
        }
        for (BlockPos pos : toDestroy) {
            if (!sameClaimOrOutside(level, pistonClaim, pos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether fluid may spread from {@code source} to {@code target}.
     *
     * <p>Fluid may fill a claimed block only when the block it came from is in the same claim. That permits a claim
     * owner's own water and lava while stopping fluid from an unclaimed neighbour or another claim entering.
     */
    public boolean canFluid(Level level, BlockPos source, BlockPos target) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim targetClaim = claimAt(level, target);
        if (targetClaim == null) {
            return true;
        }
        Claim sourceClaim = claimAt(level, source);
        return sourceClaim != null && sourceClaim.key().equals(targetClaim.key());
    }

    /**
     * Whether fire may burn the block at {@code pos}.
     *
     * <p>Fire does not consume blocks inside a claim, including blocks a visitor might ignite. This is deliberately
     * conservative: it also stops a claim owner's fireplace from spreading, because vanilla fire carries no actor
     * that could be attributed to the owner.
     */
    public boolean canFire(Level level, BlockPos pos) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        return claimAt(level, pos) == null;
    }

    /**
     * Whether a hopper may pull from the listed positions.
     *
     * <p>A hopper outside a claim cannot pull from inside one, and a hopper in one claim cannot pull from another.
     * A hopper inside a claim may still pull from unclaimed ground.
     */
    public boolean canHopper(Level level, BlockPos hopperPos, List<BlockPos> sources) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim hopperClaim = claimAt(level, hopperPos);
        for (BlockPos source : sources) {
            Claim sourceClaim = claimAt(level, source);
            if (sourceClaim == null) {
                continue;
            }
            if (hopperClaim == null || !hopperClaim.key().equals(sourceClaim.key())) {
                return false;
            }
        }
        return true;
    }

    /** Whether a dispenser or dropper may emit into {@code target}. */
    public boolean canDispense(Level level, BlockPos dispenserPos, BlockPos target) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim targetClaim = claimAt(level, target);
        if (targetClaim == null) {
            return true;
        }
        Claim dispenserClaim = claimAt(level, dispenserPos);
        return dispenserClaim != null && dispenserClaim.key().equals(targetClaim.key());
    }

    /**
     * Whether a projectile may interact with the block at {@code pos}.
     *
     * <p>A projectile with a player owner is judged as that player. A projectile with no player owner, such as one
     * fired by a dispenser or a skeleton, cannot interact with a claimed block.
     */
    public boolean canProjectile(ServerLevel level, BlockPos pos, Entity owner) {
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim claim = claimAt(level, pos);
        if (claim == null) {
            return true;
        }
        ServerPlayer actor = playerActor(owner);
        return actor != null && allows(claim, actor, ProtectionAction.BUILD);
    }

    /**
     * Whether damage to a living entity is allowed.
     *
     * <p>Damage to players is never blocked here: this slice does not introduce a PvP ban. Damage to a non-player
     * entity inside a claim is judged when a player, or something a player owns, is the attacker. Environmental
     * damage, mob damage, and other non-player causes are left to vanilla, because they are not a claim-management
     * vector and blocking them would break ordinary survival behavior.
     */
    public boolean canDamage(ServerLevel level, Entity victim, DamageSource source) {
        if (victim instanceof Player) {
            return true;
        }
        if (!ready.getAsBoolean()) {
            return false;
        }
        Claim claim = claimAt(level, victim.blockPosition());
        if (claim == null) {
            return true;
        }
        ServerPlayer actor = playerActor(source.getEntity());
        if (actor == null) {
            actor = playerActor(source.getDirectEntity());
        }
        if (actor == null) {
            return true;
        }
        return allows(claim, actor, ProtectionAction.BUILD);
    }

    /** The claim at a block position, or {@code null} when the chunk is unclaimed. */
    public Claim claimAt(Level level, BlockPos pos) {
        return cache.get(ClaimKeys.at(level, pos)).orElse(null);
    }

    private boolean sameClaimOrOutside(Level level, Claim ownerClaim, BlockPos pos) {
        Claim claim = claimAt(level, pos);
        if (claim == null) {
            return true;
        }
        return ownerClaim != null && ownerClaim.key().equals(claim.key());
    }

    private boolean allows(Claim claim, ServerPlayer actor, ProtectionAction action) {
        if (bypass.test(actor)) {
            return true;
        }
        if (claim.owner() instanceof ClaimOwner.Player owner && owner.uuid().equals(actor.getUUID())) {
            return true;
        }
        return claim.trustedPermissions(actor.getUUID()).contains(action.permission());
    }

    private ServerPlayer playerActor(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return player;
        }
        if (entity instanceof PrimedTnt tnt && tnt.getOwner() instanceof ServerPlayer player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }
}
