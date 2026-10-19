package com.dave.nusacore.core.claim;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClaimModelTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID FRIEND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final ClaimKey KEY = new ClaimKey("minecraft:overworld", 3, -7);

    @Test
    void aNewClaimHasNoExpiryAndNoTrust() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);

        assertEquals(KEY, claim.key());
        assertEquals(ClaimOwner.player(OWNER), claim.owner());
        assertTrue(claim.playerOwned());
        assertTrue(claim.trusted().isEmpty());
        assertEquals(ClaimFlags.NONE, claim.flags());
        assertEquals(CREATED, claim.createdAt());
        assertNull(claim.expiresAt(), "no expiry policy is decided, so a new claim has none");
        assertTrue(claim.expiry().isEmpty());
    }

    @Test
    void anExpiryCanBeSetAndClearedWithoutInventingADefault() {
        Claim claim = Claim.created(KEY, ClaimOwner.ADMIN, CREATED);

        Claim expiring = claim.withExpiresAt(Instant.parse("2026-02-01T00:00:00Z"));
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), expiring.expiresAt());
        assertTrue(expiring.expiry().isPresent());
        assertNull(expiring.withExpiresAt(null).expiresAt());
    }

    @Test
    void anAdminClaimHasNoPlayerOwner() {
        Claim claim = Claim.created(KEY, ClaimOwner.ADMIN, CREATED);

        assertFalse(claim.playerOwned());
        assertEquals(ClaimOwner.ADMIN, claim.owner());
    }

    @Test
    void trustIsPerPlayerAndIndependentPerPermission() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)
                .withTrust(FRIEND, Set.of(TrustedPermission.BUILD, TrustedPermission.CONTAINERS));

        assertEquals(
                Set.of(TrustedPermission.BUILD, TrustedPermission.CONTAINERS),
                claim.trustedPermissions(FRIEND));
        assertEquals(Set.of(), claim.trustedPermissions(OWNER), "the owner is not a trusted entry");

        Claim narrowed = claim.withTrust(FRIEND, Set.of(TrustedPermission.USE));
        assertEquals(Set.of(TrustedPermission.USE), narrowed.trustedPermissions(FRIEND));
        assertEquals(
                Set.of(TrustedPermission.BUILD, TrustedPermission.CONTAINERS),
                claim.trustedPermissions(FRIEND),
                "the original claim is unchanged");

        assertTrue(claim.withoutTrust(FRIEND).trusted().isEmpty());
        assertSame(claim, claim.withoutTrust(UUID.randomUUID()));
    }

    @Test
    void aClaimCannotBeChangedThroughTheMapItWasBuiltFrom() {
        Map<UUID, Set<TrustedPermission>> mutable = new LinkedHashMap<>();
        mutable.put(FRIEND, new HashSet<>(Set.of(TrustedPermission.BUILD)));

        Claim claim = new Claim(KEY, ClaimOwner.player(OWNER), mutable, ClaimFlags.NONE, CREATED, null);

        mutable.get(FRIEND).add(TrustedPermission.USE);
        mutable.clear();

        assertEquals(Set.of(TrustedPermission.BUILD), claim.trustedPermissions(FRIEND));
        assertThrows(UnsupportedOperationException.class, () -> claim.trusted().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> claim.trustedPermissions(FRIEND).add(TrustedPermission.USE));
    }

    @Test
    void trustedPlayersAreSortedForDeterministicStorage() {
        UUID later = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)
                .withTrust(later, Set.of(TrustedPermission.USE))
                .withTrust(FRIEND, Set.of(TrustedPermission.BUILD));

        assertEquals(java.util.List.of(FRIEND, later), java.util.List.copyOf(claim.trustedSorted().keySet()));
    }
}
