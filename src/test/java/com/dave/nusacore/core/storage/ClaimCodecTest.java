package com.dave.nusacore.core.storage;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimFlags;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.claim.ClaimOwner;
import com.dave.nusacore.core.claim.TrustedPermission;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stored format: what a claim becomes in columns, what those columns become back, and which stored rows are
 * refused instead of guessed at.
 */
class ClaimCodecTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID FRIEND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final ClaimKey KEY = new ClaimKey("minecraft:overworld", 3, -7);

    @Test
    void aPlayerClaimRoundTripsThroughItsColumns() {
        Claim claim = new Claim(
                KEY,
                ClaimOwner.player(OWNER),
                Map.of(FRIEND, Set.of(TrustedPermission.BUILD, TrustedPermission.USE)),
                ClaimFlags.NONE,
                CREATED,
                null);

        ClaimCodec.Row row = ClaimCodec.encode(claim);
        Claim decoded = decodeValid(row, ClaimCodec.encodeTrusted(claim));

        assertEquals(claim, decoded);
        assertEquals("minecraft:overworld", row.dimension());
        assertEquals(3, row.chunkX());
        assertEquals(-7, row.chunkZ());
        assertEquals("player", row.ownerKind());
        assertEquals(OWNER.toString(), row.ownerUuid());
        assertNull(row.expiresAt(), "no expiry policy is decided, so the column stays empty");
    }

    @Test
    void theDimensionIsStoredSeparatelyFromTheChunkCoordinates() {
        Claim overworld = Claim.created(new ClaimKey("minecraft:overworld", 4, 9), ClaimOwner.ADMIN, CREATED);
        Claim nether = Claim.created(new ClaimKey("minecraft:the_nether", 4, 9), ClaimOwner.ADMIN, CREATED);

        ClaimCodec.Row first = ClaimCodec.encode(overworld);
        ClaimCodec.Row second = ClaimCodec.encode(nether);

        assertEquals("minecraft:overworld", first.dimension());
        assertEquals("minecraft:the_nether", second.dimension());
        assertEquals(4, first.chunkX());
        assertEquals(4, second.chunkX());
        assertNotEquals(first, second, "the same chunk in two dimensions is not the same stored row");
    }

    @Test
    void anAdminClaimStoresNoOwnerUuid() {
        ClaimCodec.Row row = ClaimCodec.encode(Claim.created(KEY, ClaimOwner.ADMIN, CREATED));

        assertEquals("admin", row.ownerKind());
        assertNull(row.ownerUuid());
        assertEquals(ClaimOwner.ADMIN, decodeValid(row, List.of()).owner());
    }

    @Test
    void anExpiryRoundTripsAndStaysNullWhenAbsent() {
        Claim expiring = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)
                .withExpiresAt(Instant.parse("2026-02-01T00:00:00Z"));
        ClaimCodec.Row row = ClaimCodec.encode(expiring);

        assertEquals("2026-02-01T00:00:00.000000000Z", row.expiresAt());
        assertEquals(expiring, decodeValid(row, List.of()));

        ClaimCodec.Row noExpiry = ClaimCodec.encode(Claim.created(KEY, ClaimOwner.player(OWNER), CREATED));
        assertNull(noExpiry.expiresAt());
        assertNull(decodeValid(noExpiry, List.of()).expiresAt());
    }

    @Test
    void equalClaimsAlwaysEncodeToEqualColumns() {
        Map<UUID, Set<TrustedPermission>> first = new LinkedHashMap<>();
        first.put(OTHER, Set.of(TrustedPermission.USE));
        first.put(FRIEND, Set.of(TrustedPermission.CONTAINERS, TrustedPermission.BUILD));
        Map<UUID, Set<TrustedPermission>> second = new LinkedHashMap<>();
        second.put(FRIEND, Set.of(TrustedPermission.BUILD, TrustedPermission.CONTAINERS));
        second.put(OTHER, Set.of(TrustedPermission.USE));

        Claim claimOne = new Claim(KEY, ClaimOwner.player(OWNER), first, ClaimFlags.NONE, CREATED, null);
        Claim claimTwo = new Claim(KEY, ClaimOwner.player(OWNER), second, ClaimFlags.NONE, CREATED, null);

        assertEquals(ClaimCodec.encode(claimOne), ClaimCodec.encode(claimTwo));
        assertEquals(ClaimCodec.encodeTrusted(claimOne), ClaimCodec.encodeTrusted(claimTwo));
    }

    @Test
    void trustedRowsAreWrittenInPlayerOrderWithPermissionsInDeclarationOrder() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)
                .withTrust(OTHER, Set.of(TrustedPermission.USE))
                .withTrust(FRIEND, Set.of(TrustedPermission.USE, TrustedPermission.BUILD));

        List<ClaimCodec.TrustedRow> rows = ClaimCodec.encodeTrusted(claim);

        assertEquals(
                List.of(
                        new ClaimCodec.TrustedRow(FRIEND.toString(), "build,use"),
                        new ClaimCodec.TrustedRow(OTHER.toString(), "use")),
                rows);
        assertEquals(claim, decodeValid(ClaimCodec.encode(claim), rows));
    }

    @Test
    void aClaimNobodyIsTrustedWithWritesNoTrustedRows() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);

        assertTrue(ClaimCodec.encodeTrusted(claim).isEmpty());
        assertEquals("", ClaimCodec.encode(claim).flags());
    }

    @Test
    void flagNamesAreStoredSortedAndReadBack() {
        ClaimFlags flags = new ClaimFlags(Set.of("keep_items", "allow_visitors"));

        assertEquals("allow_visitors,keep_items", flags.serialize());
        assertEquals(flags, ClaimFlags.parse(flags.serialize()));
        assertEquals(ClaimFlags.NONE, ClaimFlags.parse(""));
    }

    @Test
    void theStoredInstantFormIsFixedWidthAndSortsChronologically() {
        Instant earlier = Instant.parse("1999-12-31T23:59:59.999999999Z");
        Instant later = Instant.parse("2000-01-01T00:00:00Z");

        String earlierText = ClaimCodec.formatInstant(earlier);
        String laterText = ClaimCodec.formatInstant(later);

        assertEquals("1999-12-31T23:59:59.999999999Z", earlierText);
        assertEquals("2000-01-01T00:00:00.000000000Z", laterText);
        assertEquals(earlierText.length(), laterText.length());
        assertTrue(earlierText.compareTo(laterText) < 0, "text order must match time order");
        assertEquals(earlier, ClaimCodec.parseInstant(earlierText));
        assertEquals(later, ClaimCodec.parseInstant(laterText));
    }

    @Test
    void anInstantOutsideTheStorableRangeOrPrecisionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ClaimCodec.formatInstant(Instant.EPOCH.minusNanos(1)));
        assertThrows(RuntimeException.class, () -> ClaimCodec.formatInstant(Instant.parse("10000-01-01T00:00:00Z")));
        assertThrows(NullPointerException.class, () -> ClaimCodec.formatInstant(null));
    }

    @Test
    void aStoredInstantThatIsNotInTheStoredFormIsReportedAsInvalid() {
        assertInvalidWithOwnedField(row("2026-01-02T03:04:05Z", null), "created_at", List.of());
        assertInvalidWithOwnedField(row("2026-01-02 03:04:05.000000000Z", null), "created_at", List.of());
        assertInvalidWithOwnedField(row("not a time", null), "created_at", List.of());
        assertInvalidWithOwnedField(row(CREATED_TEXT, "2026-02-01T00:00:00Z"), "expires_at", List.of());
    }

    @Test
    void aStoredOwnerThatDoesNotMatchItsKindIsReportedAsInvalid() {
        ClaimCodec.Row noUuid = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "player", null, "", CREATED_TEXT, null);
        ClaimCodec.Row badUuid = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "player", "not-a-uuid", "", CREATED_TEXT, null);
        ClaimCodec.Row uppercaseUuid = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "player", FRIEND.toString().toUpperCase(), "", CREATED_TEXT, null);
        ClaimCodec.Row adminWithUuid = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "admin", OWNER.toString(), "", CREATED_TEXT, null);
        ClaimCodec.Row unknownKind = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "guild", null, "", CREATED_TEXT, null);

        assertInvalidWithOwnedField(noUuid, "owner", List.of());
        assertInvalidWithOwnedField(badUuid, "owner", List.of());
        assertInvalidWithOwnedField(uppercaseUuid, "owner", List.of());
        assertInvalidWithOwnedField(adminWithUuid, "owner", List.of());
        assertInvalidWithOwnedField(unknownKind, "owner kind", List.of());
    }

    @Test
    void aStoredKeyThatIsNotANamespacedIdentifierIsReportedAsInvalid() {
        ClaimCodec.Row packedOnly = new ClaimCodec.Row(
                "overworld", 0, 0, "admin", null, "", CREATED_TEXT, null);
        ClaimCodec.Row emptyDimension = new ClaimCodec.Row(
                "", 0, 0, "admin", null, "", CREATED_TEXT, null);

        assertInvalidWithOwnedField(packedOnly, "claim key", List.of());
        assertInvalidWithOwnedField(emptyDimension, "claim key", List.of());
    }

    @Test
    void anUnreadableFlagOrTrustedRowIsReportedAsInvalid() {
        ClaimCodec.Row flagged = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "admin", null, "Not A Flag", CREATED_TEXT, null);
        assertInvalidWithOwnedField(flagged, "flags", List.of());

        ClaimCodec.Row repeatedFlag = new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "admin", null, "a,a", CREATED_TEXT, null);
        assertInvalidWithOwnedField(repeatedFlag, "flags", List.of());

        ClaimCodec.Row plain = row(CREATED_TEXT, null);
        assertInvalidWithOwnedField(
                plain, "trusted player", List.of(new ClaimCodec.TrustedRow("not-a-uuid", "build")));
        assertInvalidWithOwnedField(
                plain, "trusted player", List.of(new ClaimCodec.TrustedRow(FRIEND.toString(), "fly")));
        assertInvalidWithOwnedField(
                plain, "trusted player", List.of(new ClaimCodec.TrustedRow(FRIEND.toString(), "build,build")));
    }

    @Test
    void aMissingRowIsReportedAsInvalidRatherThanThrowing() {
        ClaimCodec.Decoded decoded = ClaimCodec.decode(null, List.of());

        assertInstanceOf(ClaimCodec.Decoded.Invalid.class, decoded);
    }

    @Test
    void encodingAMissingClaimIsACallerError() {
        assertThrows(NullPointerException.class, () -> ClaimCodec.encode(null));
        assertThrows(NullPointerException.class, () -> ClaimCodec.encodeTrusted(null));
    }

    private static final String CREATED_TEXT = "2026-01-02T03:04:05.123456789Z";

    private static ClaimCodec.Row row(String createdAt, String expiresAt) {
        return new ClaimCodec.Row(
                "minecraft:overworld", 0, 0, "admin", null, "", createdAt, expiresAt);
    }

    private static Claim decodeValid(ClaimCodec.Row row, List<ClaimCodec.TrustedRow> trusted) {
        ClaimCodec.Decoded decoded = ClaimCodec.decode(row, trusted);
        assertInstanceOf(ClaimCodec.Decoded.Valid.class, decoded, () -> "expected a readable row, got " + decoded);
        return ((ClaimCodec.Decoded.Valid) decoded).claim();
    }

    private static void assertInvalidWithOwnedField(
            ClaimCodec.Row row, String expectedReasonFragment, List<ClaimCodec.TrustedRow> trusted) {
        ClaimCodec.Decoded decoded = ClaimCodec.decode(row, trusted);
        assertInstanceOf(ClaimCodec.Decoded.Invalid.class, decoded, () -> "expected an invalid row, got " + decoded);
        String reason = ((ClaimCodec.Decoded.Invalid) decoded).reason();
        assertTrue(
                reason.contains(expectedReasonFragment),
                () -> "reason should mention '" + expectedReasonFragment + "', got: " + reason);
    }
}
