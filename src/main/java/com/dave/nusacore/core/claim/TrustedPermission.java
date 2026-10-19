package com.dave.nusacore.core.claim;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * One access permission a claim owner may grant a trusted player.
 *
 * <p>Trust grants access, never management. A trusted player can never change ownership, add or remove trust, delete
 * the claim, or rename it; those stay with the owner. Each permission is independent, so granting build access does
 * not silently grant container access.
 *
 * <p>The stored form is a comma-separated list of stable tokens in declaration order, so the same permissions always
 * serialize to the same text and a persisted row can be compared exactly.
 */
public enum TrustedPermission {
    /** Place and break blocks. */
    BUILD("build"),
    /** Open containers and other interactable blocks. */
    CONTAINERS("containers"),
    /** Use an item on a block, such as a bucket, bonemeal, or a spawn egg. */
    USE("use");

    private static final Map<String, TrustedPermission> BY_TOKEN = byToken();

    private final String token;

    TrustedPermission(String token) {
        this.token = token;
    }

    /** The stable stored form. It must not change once a server has rows using it. */
    public String token() {
        return token;
    }

    /**
     * Reads a persisted permission set.
     *
     * @throws IllegalArgumentException when a token is unknown or repeated, so a corrupt row is reported instead of
     *         being silently widened or narrowed
     */
    public static Set<TrustedPermission> parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            return Set.of();
        }
        java.util.EnumSet<TrustedPermission> permissions = java.util.EnumSet.noneOf(TrustedPermission.class);
        for (String token : text.split(",", -1)) {
            TrustedPermission permission = BY_TOKEN.get(token);
            if (permission == null) {
                throw new IllegalArgumentException("unknown claim permission token: " + token);
            }
            if (!permissions.add(permission)) {
                throw new IllegalArgumentException("repeated claim permission token: " + token);
            }
        }
        return permissions;
    }

    private static Map<String, TrustedPermission> byToken() {
        Map<String, TrustedPermission> byToken = new TreeMap<>();
        for (TrustedPermission permission : values()) {
            if (byToken.put(permission.token, permission) != null) {
                throw new IllegalStateException("duplicate claim permission token: " + permission.token);
            }
        }
        return Map.copyOf(byToken);
    }
}
