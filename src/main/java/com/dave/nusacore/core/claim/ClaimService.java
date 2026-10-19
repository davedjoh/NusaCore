package com.dave.nusacore.core.claim;

import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Claim rules on top of the durable {@link ClaimStore}: the one free chunk per player, owner-only management, and
 * trust changes.
 *
 * <p>The free allowance is one player-owned chunk globally, not one per dimension. Administrator claims are exempt:
 * they have no player owner and are limited only by the chunks they cover.
 *
 * <p>Two kinds of coordination keep those rules true under concurrent requests:
 * <ul>
 *   <li>A per-owner reservation is taken before a create starts and released only after the store future completes.
 *       The store completes after the cache has been activated, so a second request cannot slip into the gap between
 *       the commit and the cache update. Reservations span dimensions because the owner set is not keyed by chunk.</li>
 *   <li>Every mutation of one claim key runs through one serialized tail, so a trust update always reads the claim
 *       the previous update left behind. Without that, two concurrent {@code /trust} calls could each write a copy
 *       that lacks the other player.</li>
 * </ul>
 *
 * <p>Every rule is checked again after the serialization gate opens, because the cache may have changed while the
 * request waited. A rejected request never writes, and a successful result is only produced after the durable commit
 * and cache activation.
 */
public final class ClaimService {
    private final ClaimStore store;
    private final Clock clock;

    /** Guards both coordination maps below. */
    private final Object coordination = new Object();

    /** Players with a create in flight, so two chunks cannot both become their free claim. */
    private final Set<UUID> ownerReservations = new HashSet<>();

    /** The last queued mutation per key; the next mutation waits for it. */
    private final Map<ClaimKey, CompletableFuture<Void>> keyTails = new HashMap<>();

    public ClaimService(ClaimStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The committed claim at a key, from the cache only. */
    public Optional<Claim> claimAt(ClaimKey key) {
        return store.findCached(key);
    }

    /** True when the player owns a claim anywhere, in any dimension. */
    public boolean ownsAnyClaim(UUID player) {
        Objects.requireNonNull(player, "player");
        for (Claim claim : store.cachedClaims()) {
            if (claim.owner() instanceof ClaimOwner.Player owner && owner.uuid().equals(player)) {
                return true;
            }
        }
        return false;
    }

    /** Creates the player's one free claim at {@code key}. */
    public CompletableFuture<ClaimResult> claimPlayer(UUID player, ClaimKey key) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        return serialized(key, () -> {
            ClaimResult refused = commonCreateChecks(key);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            if (ownsAnyClaim(player) || !reserveOwner(player)) {
                return CompletableFuture.completedFuture(ClaimResult.failure(Messages.CLAIM_ALREADY_OWNED));
            }
            Claim claim = Claim.created(key, ClaimOwner.player(player), clock.instant());
            CompletableFuture<ClaimResult> result = store.create(claim).thenApply(ClaimService::created);
            result.whenComplete((ignored, failure) -> releaseOwner(player));
            return result;
        });
    }

    /** Creates an administrator claim at {@code key}. Administrator claims are exempt from the player allowance. */
    public CompletableFuture<ClaimResult> claimAdmin(ClaimKey key) {
        Objects.requireNonNull(key, "key");
        return serialized(key, () -> {
            ClaimResult refused = commonCreateChecks(key);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            Claim claim = Claim.created(key, ClaimOwner.ADMIN, clock.instant());
            return store.create(claim).thenApply(ClaimService::createdAdmin);
        });
    }

    /** Removes the player's own claim at {@code key}. */
    public CompletableFuture<ClaimResult> unclaimPlayer(UUID player, ClaimKey key) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        return serialized(key, () -> {
            ClaimResult refused = commonExistingChecks(key);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            Claim claim = store.findCached(key).orElseThrow();
            if (!(claim.owner() instanceof ClaimOwner.Player owner) || !owner.uuid().equals(player)) {
                return CompletableFuture.completedFuture(ClaimResult.failure(Messages.CLAIM_NOT_OWNER));
            }
            return store.delete(key).thenApply(ClaimService::removed);
        });
    }

    /** Removes any claim at {@code key}. The caller must have checked the administrator permission. */
    public CompletableFuture<ClaimResult> removeAdmin(ClaimKey key) {
        Objects.requireNonNull(key, "key");
        return serialized(key, () -> {
            ClaimResult refused = commonExistingChecks(key);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            return store.delete(key).thenApply(ClaimService::adminRemoved);
        });
    }

    /**
     * Adds permissions to one trusted player on the owner's claim.
     *
     * <p>The permissions are merged with what the player already has, so granting one scope never silently removes
     * another.
     */
    public CompletableFuture<ClaimResult> trust(UUID owner, ClaimKey key, UUID trusted,
            Set<TrustedPermission> permissions) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(trusted, "trusted");
        Objects.requireNonNull(permissions, "permissions");
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("at least one permission is required");
        }
        return serialized(key, () -> {
            ClaimResult refused = commonExistingChecks(key);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            Claim claim = store.findCached(key).orElseThrow();
            ClaimResult ownership = requireOwner(claim, owner);
            if (ownership != null) {
                return CompletableFuture.completedFuture(ownership);
            }
            if (owner.equals(trusted)) {
                return CompletableFuture.completedFuture(ClaimResult.failure(Messages.CLAIM_TRUST_SELF));
            }
            Set<TrustedPermission> existing = claim.trustedPermissions(trusted);
            Set<TrustedPermission> merged = EnumSet.noneOf(TrustedPermission.class);
            merged.addAll(existing);
            merged.addAll(permissions);
            if (merged.equals(existing)) {
                return CompletableFuture.completedFuture(ClaimResult.failure(Messages.CLAIM_TRUST_ALREADY, trusted));
            }
            Claim updated = claim.withTrust(trusted, merged);
            return store.update(updated).thenApply(result -> result.ok()
                    ? ClaimResult.success(Messages.CLAIM_TRUST_ADDED, trusted)
                    : failed(result));
        });
    }

    /** Removes one player from the owner's trusted list, with all of their permissions. */
    public CompletableFuture<ClaimResult> untrust(UUID owner, ClaimKey key, UUID trusted) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(trusted, "trusted");
        return serialized(key, () -> {
            ClaimResult refused = commonExistingChecks(key);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            Claim claim = store.findCached(key).orElseThrow();
            ClaimResult ownership = requireOwner(claim, owner);
            if (ownership != null) {
                return CompletableFuture.completedFuture(ownership);
            }
            if (!claim.trusted().containsKey(trusted)) {
                return CompletableFuture.completedFuture(ClaimResult.failure(Messages.CLAIM_TRUST_NOT_FOUND, trusted));
            }
            Claim updated = claim.withoutTrust(trusted);
            return store.update(updated).thenApply(result -> result.ok()
                    ? ClaimResult.success(Messages.CLAIM_TRUST_REMOVED, trusted)
                    : failed(result));
        });
    }

    /** True when the store is loaded, so a cache miss really means unclaimed. */
    public boolean ready() {
        return store.ready();
    }

    /** The store this service writes through, for the protection read path. */
    public ClaimStore store() {
        return store;
    }

    private ClaimResult commonCreateChecks(ClaimKey key) {
        if (!store.ready()) {
            return ClaimResult.failure(Messages.CLAIM_STORAGE_NOT_READY);
        }
        if (store.findCached(key).isPresent()) {
            return ClaimResult.failure(Messages.CLAIM_CHUNK_TAKEN);
        }
        return null;
    }

    private ClaimResult commonExistingChecks(ClaimKey key) {
        if (!store.ready()) {
            return ClaimResult.failure(Messages.CLAIM_STORAGE_NOT_READY);
        }
        if (store.findCached(key).isEmpty()) {
            return ClaimResult.failure(Messages.CLAIM_NOT_CLAIMED);
        }
        return null;
    }

    private static ClaimResult requireOwner(Claim claim, UUID owner) {
        if (claim.owner() instanceof ClaimOwner.Player player && player.uuid().equals(owner)) {
            return null;
        }
        return ClaimResult.failure(Messages.CLAIM_NOT_OWNER);
    }

    private boolean reserveOwner(UUID player) {
        synchronized (coordination) {
            return ownerReservations.add(player);
        }
    }

    private void releaseOwner(UUID player) {
        synchronized (coordination) {
            ownerReservations.remove(player);
        }
    }

    /**
     * Runs one mutation of {@code key} after every mutation already queued for that key.
     *
     * <p>The gate is completed only after the action's future completes, so the next action sees the cache state
     * the previous commit produced. The tail is removed when it is still the last one, so an idle key leaves no
     * entry behind.
     */
    private CompletableFuture<ClaimResult> serialized(ClaimKey key, Supplier<CompletableFuture<ClaimResult>> action) {
        CompletableFuture<ClaimResult> result = new CompletableFuture<>();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> previous;
        synchronized (coordination) {
            previous = keyTails.getOrDefault(key, CompletableFuture.completedFuture(null));
            keyTails.put(key, gate);
        }
        previous.whenComplete((ignored, previousFailure) -> {
            CompletableFuture<ClaimResult> started;
            try {
                started = action.get();
            } catch (RuntimeException failure) {
                result.complete(ClaimResult.failure(Messages.CLAIM_STORAGE_ERROR));
                finish(key, gate);
                return;
            }
            started.whenComplete((value, failure) -> {
                if (failure != null) {
                    result.complete(ClaimResult.failure(Messages.CLAIM_STORAGE_ERROR));
                } else {
                    result.complete(value);
                }
                finish(key, gate);
            });
        });
        return result;
    }

    private void finish(ClaimKey key, CompletableFuture<Void> gate) {
        synchronized (coordination) {
            if (keyTails.get(key) == gate) {
                keyTails.remove(key);
            }
        }
        gate.complete(null);
    }

    private static ClaimResult created(StorageResult<Void> result) {
        return result.ok() ? ClaimResult.success(Messages.CLAIM_CREATED) : failed(result);
    }

    private static ClaimResult createdAdmin(StorageResult<Void> result) {
        return result.ok() ? ClaimResult.success(Messages.CLAIM_ADMIN_CREATED) : failed(result);
    }

    private static ClaimResult removed(StorageResult<Void> result) {
        return result.ok() ? ClaimResult.success(Messages.CLAIM_REMOVED) : failed(result);
    }

    private static ClaimResult adminRemoved(StorageResult<Void> result) {
        return result.ok() ? ClaimResult.success(Messages.CLAIM_ADMIN_REMOVED) : failed(result);
    }

    private static ClaimResult failed(StorageResult<?> result) {
        StorageFailure failure = result.failureOrNull();
        if (failure == StorageFailure.DUPLICATE_KEY) {
            return ClaimResult.failure(Messages.CLAIM_CHUNK_TAKEN);
        }
        if (failure == StorageFailure.NOT_FOUND) {
            return ClaimResult.failure(Messages.CLAIM_NOT_CLAIMED);
        }
        if (failure == StorageFailure.CLOSED) {
            return ClaimResult.failure(Messages.CLAIM_STORAGE_UNAVAILABLE);
        }
        return ClaimResult.failure(Messages.CLAIM_STORAGE_ERROR);
    }

    /** The instant source, exposed so tests can assert that no expiry was invented. */
    Instant now() {
        return clock.instant();
    }
}
