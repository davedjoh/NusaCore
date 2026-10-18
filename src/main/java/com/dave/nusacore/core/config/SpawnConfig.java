package com.dave.nusacore.core.config;

/**
 * Tunable spawn behavior. These are the in-memory defaults for this slice; loading them from a file is future
 * work and must not run on the server thread when it is added.
 *
 * <p>Both values are deliberately bounded. The search only reads chunks that are already loaded, so a large
 * radius cannot force chunk generation, and the scan depth caps how far one column is inspected.
 */
public record SpawnConfig(int horizontalSearchRadius, int scanDepth) {
    public static final int MAX_HORIZONTAL_SEARCH_RADIUS = 24;
    public static final int MAX_SCAN_DEPTH = 16;
    public static final SpawnConfig DEFAULT = new SpawnConfig(8, 5);

    public SpawnConfig {
        if (horizontalSearchRadius < 0 || horizontalSearchRadius > MAX_HORIZONTAL_SEARCH_RADIUS) {
            throw new IllegalArgumentException(
                    "horizontalSearchRadius must be between 0 and " + MAX_HORIZONTAL_SEARCH_RADIUS);
        }
        if (scanDepth < 1 || scanDepth > MAX_SCAN_DEPTH) {
            throw new IllegalArgumentException("scanDepth must be between 1 and " + MAX_SCAN_DEPTH);
        }
    }
}
