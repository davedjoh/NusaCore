package com.dave.nusacore.core.cache;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimKey;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The committed claims, held in memory so the game thread never touches storage.
 *
 * <p>Reads ({@link #get}, {@link #size}, {@link #snapshot}) are lock-free and never block or do I/O, so they are safe
 * on the server tick thread. They see only claims whose durable write has already succeeded.
 *
 * <p>Writes are named for their ordering: {@link #activateAfterCommit} and {@link #removeAfterCommit} must only be
 * called after the repository reported success. There is deliberately no method that inserts a claim before the
 * commit, so an optimistic cache-only write cannot happen through this API.
 *
 * <p>The cache is not ready until the initial load finishes. A caller that must not act on a partial view checks
 * {@link #ready()} first.
 */
public final class ClaimCache {
    private volatile Map<ClaimKey, Claim> claims = Map.of();
    private volatile boolean ready;

    /** True once the initial load has finished and reads describe the whole stored set. */
    public boolean ready() {
        return ready;
    }

    /** How many claims are cached. */
    public int size() {
        return claims.size();
    }

    /** The claim at a key, or empty when that chunk is unclaimed. */
    public Optional<Claim> get(ClaimKey key) {
        return Optional.ofNullable(claims.get(key));
    }

    /** The claim at a dimension and chunk, or empty when that chunk is unclaimed. */
    public Optional<Claim> get(String dimension, int chunkX, int chunkZ) {
        return get(new ClaimKey(dimension, chunkX, chunkZ));
    }

    /** An immutable snapshot of every cached claim, for diagnostics and tests. */
    public List<Claim> snapshot() {
        return List.copyOf(claims.values());
    }

    /**
     * Replaces the cache with the result of the initial load and marks it ready.
     *
     * <p>Called once at server start, before any mutation is accepted. A later call replaces the whole view, which
     * keeps a reload from mixing old and new state.
     */
    public synchronized void loadAll(Collection<Claim> loaded) {
        Map<ClaimKey, Claim> next = new HashMap<>(loaded.size());
        for (Claim claim : loaded) {
            next.put(claim.key(), claim);
        }
        claims = Map.copyOf(next);
        ready = true;
    }

    /**
     * Adds or replaces one claim in the cache.
     *
     * <p>Only call this after the durable write succeeded. The method name states the ordering so a caller cannot
     * miss it, and the repository result is what decides whether this runs at all.
     */
    public synchronized void activateAfterCommit(Claim claim) {
        Map<ClaimKey, Claim> next = new HashMap<>(claims);
        next.put(claim.key(), claim);
        claims = Map.copyOf(next);
    }

    /**
     * Removes one claim from the cache.
     *
     * <p>Only call this after the durable delete succeeded. Removing a key that is not cached is a no-op, which
     * keeps a retried delete harmless.
     */
    public synchronized void removeAfterCommit(ClaimKey key) {
        if (!claims.containsKey(key)) {
            return;
        }
        Map<ClaimKey, Claim> next = new HashMap<>(claims);
        next.remove(key);
        claims = Map.copyOf(next);
    }
}
