package com.dave.nusacore.core.cache;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimFlags;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.claim.ClaimOwner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The committed in-memory view: it starts empty and unready, only becomes ready on a load, and only changes through
 * the after-commit methods.
 */
class ClaimCacheTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final ClaimKey KEY = new ClaimKey("minecraft:overworld", 1, 2);

    @Test
    void aFreshCacheHoldsNothingAndIsNotReady() {
        ClaimCache cache = new ClaimCache();

        assertFalse(cache.ready(), "an empty cache must not look like an empty world");
        assertEquals(0, cache.size());
        assertTrue(cache.get(KEY).isEmpty());
        assertTrue(cache.snapshot().isEmpty());
    }

    @Test
    void loadingMarksTheCacheReadyAndFillsIt() {
        ClaimCache cache = new ClaimCache();
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);

        cache.loadAll(List.of(claim));

        assertTrue(cache.ready());
        assertEquals(1, cache.size());
        assertEquals(claim, cache.get(KEY).orElseThrow());
        assertEquals(claim, cache.get("minecraft:overworld", 1, 2).orElseThrow());
    }

    @Test
    void aLoadReplacesTheWholeView() {
        ClaimCache cache = new ClaimCache();
        Claim first = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        Claim second = Claim.created(new ClaimKey("minecraft:the_nether", 1, 2), ClaimOwner.ADMIN, CREATED);

        cache.loadAll(List.of(first, second));
        assertEquals(2, cache.size());

        cache.loadAll(List.of(second));

        assertEquals(1, cache.size());
        assertTrue(cache.get(KEY).isEmpty(), "a reload must not leave claims from the previous view behind");
        assertEquals(second, cache.get(second.key()).orElseThrow());
    }

    @Test
    void dimensionsAreNotConflated() {
        ClaimCache cache = new ClaimCache();
        Claim overworld = Claim.created(new ClaimKey("minecraft:overworld", 5, 6), ClaimOwner.ADMIN, CREATED);
        Claim nether = Claim.created(new ClaimKey("minecraft:the_nether", 5, 6), ClaimOwner.ADMIN, CREATED);

        cache.loadAll(List.of(overworld, nether));

        assertEquals(overworld, cache.get("minecraft:overworld", 5, 6).orElseThrow());
        assertEquals(nether, cache.get("minecraft:the_nether", 5, 6).orElseThrow());
        assertEquals(2, cache.size());
    }

    @Test
    void aClaimIsVisibleOnlyAfterItsActivation() {
        ClaimCache cache = new ClaimCache();
        cache.loadAll(List.of());
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);

        assertTrue(cache.get(KEY).isEmpty());

        cache.activateAfterCommit(claim);

        assertEquals(claim, cache.get(KEY).orElseThrow());
    }

    @Test
    void activationReplacesAClaimAtTheSameKey() {
        ClaimCache cache = new ClaimCache();
        Claim original = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        Claim updated = original.withExpiresAt(Instant.parse("2026-03-01T00:00:00Z"));
        cache.loadAll(List.of(original));

        cache.activateAfterCommit(updated);

        assertEquals(1, cache.size());
        assertEquals(updated, cache.get(KEY).orElseThrow());
    }

    @Test
    void removalIsIdempotent() {
        ClaimCache cache = new ClaimCache();
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        cache.loadAll(List.of(claim));

        cache.removeAfterCommit(KEY);
        cache.removeAfterCommit(KEY);

        assertTrue(cache.get(KEY).isEmpty());
        assertEquals(0, cache.size());
        assertTrue(cache.ready(), "a removal does not unload the cache");
    }

    @Test
    void aSnapshotCannotBeMutatedThroughTheCache() {
        ClaimCache cache = new ClaimCache();
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        cache.loadAll(List.of(claim));

        List<Claim> snapshot = cache.snapshot();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(claim));
    }

    @Test
    void claimsSurviveWithTheirTrustAndFlags() {
        ClaimCache cache = new ClaimCache();
        UUID friend = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Claim claim = new Claim(
                KEY,
                ClaimOwner.player(OWNER),
                Map.of(friend, java.util.Set.of(com.dave.nusacore.core.claim.TrustedPermission.BUILD)),
                ClaimFlags.NONE,
                CREATED,
                null);

        cache.loadAll(List.of(claim));

        assertEquals(claim, cache.get(KEY).orElseThrow());
    }
}
