package com.dave.nusacore.core.claim;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The per-claim flags: named toggles that will later relax or tighten protection.
 *
 * <p>No flag is declared here yet. Claim protection and every behavior a flag could switch are later slices, and
 * naming a flag now would decide gameplay that has not been agreed. The representation is still real and
 * extensible: a flag is a stable lowercase name, stored as a canonical comma-separated list, and adding a typed
 * flag later is a small code change that leaves existing rows readable.
 *
 * <p>The set is validated, sorted, and immutable, so equal flags always serialize to the same text.
 */
public record ClaimFlags(Set<String> names) {
    /** No flags set, which is the initial state of every claim. */
    public static final ClaimFlags NONE = new ClaimFlags(Set.of());

    /** Most flags one claim may carry, so a corrupt or hostile row stays bounded. */
    public static final int MAX_FLAGS = 16;

    /** Longest flag name, and the characters it may use. */
    private static final int MAX_NAME_LENGTH = 32;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+");

    public ClaimFlags {
        Objects.requireNonNull(names, "names");
        if (names.size() > MAX_FLAGS) {
            throw new IllegalArgumentException("a claim may carry at most " + MAX_FLAGS + " flags");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String name : names) {
            if (name == null
                    || name.isEmpty()
                    || name.length() > MAX_NAME_LENGTH
                    || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid claim flag name: " + name);
            }
            sorted.add(name);
        }
        names = Collections.unmodifiableSet(sorted);
    }

    /** True when no flag is set. */
    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** True when the named flag is set. */
    public boolean contains(String name) {
        return names.contains(name);
    }

    /** The stored form: flag names in sorted order, comma separated, empty for no flags. */
    public String serialize() {
        return String.join(",", names);
    }

    /**
     * Reads a stored flag string.
     *
     * @throws IllegalArgumentException when a name is malformed or repeated, so a corrupt row is reported instead
     *         of silently changing which flags a claim has
     */
    public static ClaimFlags parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            return NONE;
        }
        TreeSet<String> names = new TreeSet<>();
        for (String name : text.split(",", -1)) {
            if (!names.add(name)) {
                throw new IllegalArgumentException("repeated claim flag: " + name);
            }
        }
        return new ClaimFlags(names);
    }
}
