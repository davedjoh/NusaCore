package com.dave.nusacore.core.claim;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Claim identity: one dimension plus one chunk column.
 *
 * <p>The dimension is a first-class part of the key and is stored as its namespaced identifier, never folded into
 * a packed chunk coordinate. The same chunk x/z in the Nether and the Overworld are two different claims, and the
 * packed-long form vanilla uses for chunk keys is never a storage key on its own.
 *
 * <p>The identifier is validated here, so a malformed key cannot reach the database and an unreadable row fails as
 * a domain result instead of a driver exception.
 */
public record ClaimKey(String dimension, int chunkX, int chunkZ) {
    /** Longest identifier accepted, generous enough for a datapack dimension such as {@code mypack:deep_caverns}. */
    public static final int MAX_DIMENSION_LENGTH = 128;

    /** {@code namespace:path}, limited to the characters Minecraft allows in a resource location. */
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public ClaimKey {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.length() > MAX_DIMENSION_LENGTH || !DIMENSION.matcher(dimension).matches()) {
            throw new IllegalArgumentException("dimension must be a namespaced identifier, got: " + dimension);
        }
    }

    /** Readable form for logs and test failures. */
    @Override
    public String toString() {
        return dimension + "@" + chunkX + "," + chunkZ;
    }
}
