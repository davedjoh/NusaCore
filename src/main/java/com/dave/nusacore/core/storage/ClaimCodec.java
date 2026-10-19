package com.dave.nusacore.core.storage;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimFlags;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.claim.ClaimOwner;
import com.dave.nusacore.core.claim.TrustedPermission;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one place a {@link Claim} becomes column text and back.
 *
 * <p>This is a pure function pair with no database or driver dependency. The SQLite mapper converts types and calls
 * this, so the stored format is defined once, can be tested without a database, and can be reused by a future
 * backend.
 *
 * <p>Determinism: equal claims always produce equal column values. Timestamps use a fixed-width UTC ISO-8601 form
 * so they compare lexicographically in the same order as chronologically, trusted players are written in UUID
 * order, and permission lists follow declaration order.
 *
 * <p>Failures are values, not exceptions. A malformed dimension, owner, UUID, timestamp, or permission list yields
 * {@link Decoded.Invalid} with a reason, and the repository turns that into a normal failure result. Only caller
 * errors, such as a {@code null} claim, throw.
 */
public final class ClaimCodec {
    /** Stored form of a player-owned claim. */
    public static final String OWNER_PLAYER = "player";

    /** Stored form of an administrator claim, which has no player UUID. */
    public static final String OWNER_ADMIN = "admin";

    /**
     * The stored instant format: UTC, fixed width, nanosecond precision. Fixed width keeps text comparison in
     * chronological order, which a future expiry scan can rely on.
     */
    private static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** Oldest storable instant: 1970-01-01T00:00:00Z. */
    private static final Instant MIN_INSTANT = Instant.EPOCH;

    /** Newest storable instant: 9999-12-31T23:59:59.999999999Z, the end of the four-digit year range. */
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ClaimCodec() {
    }

    /** The claims table columns other than the row id. Trusted players live in their own table. */
    public record Row(
            String dimension,
            int chunkX,
            int chunkZ,
            String ownerKind,
            String ownerUuid,
            String flags,
            String createdAt,
            String expiresAt) {
    }

    /** One trusted-player row: the player and the permissions granted to them. */
    public record TrustedRow(String playerUuid, String permissions) {
    }

    /** The outcome of reading a stored row: a claim, or the reason the row is unusable. */
    public sealed interface Decoded permits Decoded.Valid, Decoded.Invalid {
        /** The row is internally consistent. */
        record Valid(Claim claim) implements Decoded {
        }

        /** The row exists but cannot be read as a claim. It is reported, never guessed at. */
        record Invalid(String reason) implements Decoded {
            public Invalid {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("an invalid row needs a reason");
                }
            }
        }
    }

    /**
     * Renders the claim columns.
     *
     * @throws NullPointerException when the claim or one of its required parts is missing, which is a programming
     *         error rather than stored data
     */
    public static Row encode(Claim claim) {
        if (claim == null) {
            throw new NullPointerException("claim");
        }
        ClaimOwner owner = claim.owner();
        boolean playerOwned = owner instanceof ClaimOwner.Player;
        String ownerUuid = playerOwned ? ((ClaimOwner.Player) owner).uuid().toString() : null;
        return new Row(
                claim.key().dimension(),
                claim.key().chunkX(),
                claim.key().chunkZ(),
                playerOwned ? OWNER_PLAYER : OWNER_ADMIN,
                ownerUuid,
                claim.flags().serialize(),
                formatInstant(claim.createdAt()),
                claim.expiresAt() == null ? null : formatInstant(claim.expiresAt()));
    }

    /**
     * Renders the trusted players of a claim, sorted by UUID.
     *
     * <p>The sort makes the stored order independent of the map's iteration order, so two equal claims write the
     * same rows in the same order.
     */
    public static List<TrustedRow> encodeTrusted(Claim claim) {
        if (claim == null) {
            throw new NullPointerException("claim");
        }
        List<TrustedRow> rows = new ArrayList<>(claim.trusted().size());
        for (Map.Entry<UUID, Set<TrustedPermission>> entry : claim.trustedSorted().entrySet()) {
            rows.add(new TrustedRow(entry.getKey().toString(), renderPermissions(entry.getValue())));
        }
        return rows;
    }

    /**
     * Reads columns back into a claim.
     *
     * @param row the claims row
     * @param trusted the trusted-player rows belonging to it
     */
    public static Decoded decode(Row row, List<TrustedRow> trusted) {
        if (row == null) {
            return new Decoded.Invalid("row is missing");
        }
        ClaimKey key;
        try {
            key = new ClaimKey(row.dimension(), row.chunkX(), row.chunkZ());
        } catch (RuntimeException invalidKey) {
            return new Decoded.Invalid("invalid claim key: " + invalidKey.getMessage());
        }

        ClaimOwner owner;
        if (OWNER_PLAYER.equals(row.ownerKind())) {
            UUID uuid;
            try {
                uuid = parseUuid(row.ownerUuid());
            } catch (RuntimeException invalidOwner) {
                return new Decoded.Invalid("invalid claim owner: " + invalidOwner.getMessage());
            }
            owner = ClaimOwner.player(uuid);
        } else if (OWNER_ADMIN.equals(row.ownerKind())) {
            if (row.ownerUuid() != null) {
                return new Decoded.Invalid("an admin claim must not store an owner uuid");
            }
            owner = ClaimOwner.ADMIN;
        } else {
            return new Decoded.Invalid("unknown claim owner kind: " + row.ownerKind());
        }

        ClaimFlags flags;
        try {
            flags = ClaimFlags.parse(row.flags());
        } catch (RuntimeException invalidFlags) {
            return new Decoded.Invalid("invalid claim flags: " + invalidFlags.getMessage());
        }

        Instant createdAt;
        try {
            createdAt = parseInstant(row.createdAt());
        } catch (RuntimeException invalidCreated) {
            return new Decoded.Invalid("invalid created_at: " + invalidCreated.getMessage());
        }

        Instant expiresAt;
        if (row.expiresAt() == null) {
            expiresAt = null;
        } else {
            try {
                expiresAt = parseInstant(row.expiresAt());
            } catch (RuntimeException invalidExpiry) {
                return new Decoded.Invalid("invalid expires_at: " + invalidExpiry.getMessage());
            }
        }

        Map<UUID, Set<TrustedPermission>> trust;
        try {
            trust = decodeTrusted(trusted);
        } catch (RuntimeException invalidTrust) {
            return new Decoded.Invalid("invalid trusted players: " + invalidTrust.getMessage());
        }

        return new Decoded.Valid(new Claim(key, owner, trust, flags, createdAt, expiresAt));
    }

    /** Reads trusted-player rows into a map, rejecting a player listed twice. */
    public static Map<UUID, Set<TrustedPermission>> decodeTrusted(List<TrustedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        TreeMap<UUID, Set<TrustedPermission>> trust = new TreeMap<>();
        for (TrustedRow row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("trusted row is missing");
            }
            UUID player = parseUuid(row.playerUuid());
            if (trust.put(player, parsePermissions(row.permissions())) != null) {
                throw new IllegalArgumentException("trusted player is listed twice: " + player);
            }
        }
        return new LinkedHashMap<>(trust);
    }

    /** Renders one permission set in declaration order, empty for no permissions. */
    public static String renderPermissions(Set<TrustedPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (TrustedPermission permission : TrustedPermission.values()) {
            if (permissions.contains(permission)) {
                if (!text.isEmpty()) {
                    text.append(',');
                }
                text.append(permission.token());
            }
        }
        return text.toString();
    }

    /** Reads one permission set. */
    public static Set<TrustedPermission> parsePermissions(String text) {
        return TrustedPermission.parse(text == null ? "" : text);
    }

    /**
     * The stored instant text.
     *
     * @throws IllegalArgumentException when the instant is outside the storable range
     */
    public static String formatInstant(Instant instant) {
        if (instant == null) {
            throw new NullPointerException("instant");
        }
        if (instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT)) {
            throw new IllegalArgumentException("instant is outside the stored range: " + instant);
        }
        return INSTANT_FORMAT.format(instant);
    }

    /**
     * Reads stored instant text.
     *
     * @throws IllegalArgumentException when the text is not a UTC instant in the stored format or range
     */
    public static Instant parseInstant(String text) {
        if (text == null) {
            throw new IllegalArgumentException("instant text is missing");
        }
        Instant instant;
        try {
            instant = Instant.parse(text);
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("not a UTC ISO-8601 instant: " + text, malformed);
        }
        if (instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT)) {
            throw new IllegalArgumentException("instant is outside the stored range: " + text);
        }
        try {
            // A value that the fixed format cannot reproduce exactly would not survive a round trip.
            if (!INSTANT_FORMAT.format(instant).equals(text)) {
                throw new IllegalArgumentException("instant is not in the stored format: " + text);
            }
        } catch (DateTimeException impossible) {
            throw new IllegalArgumentException("instant cannot be stored: " + text, impossible);
        }
        return instant;
    }

    private static UUID parseUuid(String text) {
        if (text == null || !UUID_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException("not a lowercase UUID: " + text);
        }
        return UUID.fromString(text);
    }
}
