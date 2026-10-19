package com.dave.nusacore.core.claim;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One persisted claim: which chunk in which dimension it covers, who owns it, who is trusted with what, its flags,
 * when it was created, and when it expires.
 *
 * <p>{@code expiresAt} is part of the model from the start and is nullable, but no expiry policy is decided here.
 * A claim with no expiry is the only kind anything creates today; choosing a duration, a grace period, or what
 * happens on release is open work, so nothing in this class computes or assumes one.
 *
 * <p>Timestamps are UTC instants. {@code createdAt} is always set. The record is immutable and the trusted map and
 * permission sets are defensively copied, so a persisted claim cannot change after it has been read.
 */
public record Claim(
        ClaimKey key,
        ClaimOwner owner,
        Map<UUID, Set<TrustedPermission>> trusted,
        ClaimFlags flags,
        Instant createdAt,
        Instant expiresAt) {

    public Claim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(trusted, "trusted");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(createdAt, "createdAt");
        trusted = copyTrusted(trusted);
    }

    /**
     * A new claim with no trusted players, no flags, and no expiry.
     *
     * <p>No expiry policy exists yet, so {@code expiresAt} is left {@code null} rather than filled with an invented
     * duration.
     *
     * @param createdAt the current UTC time, passed in so the caller owns the clock
     */
    public static Claim created(ClaimKey key, ClaimOwner owner, Instant createdAt) {
        return new Claim(key, owner, Map.of(), ClaimFlags.NONE, createdAt, null);
    }

    /**
     * This claim with an expiry, for callers that already decided one.
     *
     * @param expiresAt the UTC instant the claim expires, or {@code null} for no expiry
     */
    public Claim withExpiresAt(Instant expiresAt) {
        return new Claim(key, owner, trusted, flags, createdAt, expiresAt);
    }

    /** This claim with one trusted player's permissions added or replaced. */
    public Claim withTrust(UUID player, Set<TrustedPermission> permissions) {
        Objects.requireNonNull(player, "player");
        Map<UUID, Set<TrustedPermission>> next = new LinkedHashMap<>(trusted);
        next.put(player, permissions);
        return new Claim(key, owner, next, flags, createdAt, expiresAt);
    }

    /** This claim with one trusted player removed. Removing a player who is not trusted changes nothing. */
    public Claim withoutTrust(UUID player) {
        Objects.requireNonNull(player, "player");
        if (!trusted.containsKey(player)) {
            return this;
        }
        Map<UUID, Set<TrustedPermission>> next = new LinkedHashMap<>(trusted);
        next.remove(player);
        return new Claim(key, owner, next, flags, createdAt, expiresAt);
    }

    /** This claim with different flags. */
    public Claim withFlags(ClaimFlags flags) {
        return new Claim(key, owner, trusted, flags, createdAt, expiresAt);
    }

    /** The expiry instant, or empty when the claim has none. Expiry is stored but not yet acted on. */
    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }

    /** True when the owner is a player. An administrator claim has no player owner. */
    public boolean playerOwned() {
        return owner instanceof ClaimOwner.Player;
    }

    /** The permissions granted to one player, empty when that player is not trusted. */
    public Set<TrustedPermission> trustedPermissions(UUID player) {
        return trusted.getOrDefault(player, Set.of());
    }

    /** The trusted players and their permissions, sorted by player UUID. */
    public SortedMap<UUID, Set<TrustedPermission>> trustedSorted() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(trusted));
    }

    private static Map<UUID, Set<TrustedPermission>> copyTrusted(Map<UUID, Set<TrustedPermission>> trusted) {
        if (trusted.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<TrustedPermission>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<TrustedPermission>> entry : trusted.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "trusted player"),
                    Set.copyOf(Objects.requireNonNull(entry.getValue(), "trusted permissions")));
        }
        return Collections.unmodifiableMap(copy);
    }
}
