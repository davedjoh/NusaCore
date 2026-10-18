package com.dave.nusacore.core.spawn;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, bounded candidate search around the world spawn.
 *
 * <p>Columns are ordered by horizontal offset: square ring by ring from the spawn column outwards, and inside a
 * ring the nearest tiles first. The spawn column itself is included. The order depends only on the radius
 * settings, so the same world state always yields the same destination.
 *
 * <p>Inside a column the search starts at the feet block and scans downwards for a bounded number of blocks,
 * which is how a player caps a pillar and how the safety rules read the ground.
 */
public final class SafeSpawnSearch {
    private final List<HorizontalOffset> horizontalOffsets;
    private final int scanDepth;

    public SafeSpawnSearch(int horizontalRadius, int scanDepth) {
        if (horizontalRadius < 0) {
            throw new IllegalArgumentException("horizontalRadius must not be negative");
        }
        if (scanDepth < 1) {
            throw new IllegalArgumentException("scanDepth must be at least 1");
        }
        this.horizontalOffsets = orderHorizontally(horizontalRadius);
        this.scanDepth = scanDepth;
    }

    /** Horizontal offsets in visit order, ring by ring from the spawn column outwards. */
    public List<HorizontalOffset> horizontalOffsets() {
        return horizontalOffsets;
    }

    /** How many blocks below the feet position are inspected inside one column. */
    public int scanDepth() {
        return scanDepth;
    }

    private static List<HorizontalOffset> orderHorizontally(int radius) {
        List<HorizontalOffset> offsets = new ArrayList<>();
        for (int ring = 0; ring <= radius; ring++) {
            List<HorizontalOffset> ringOffsets = new ArrayList<>();
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        ringOffsets.add(new HorizontalOffset(dx, dz));
                    }
                }
            }
            // Nearest first, then a fixed x/z ordering so the sequence never depends on the collection type.
            ringOffsets.sort(java.util.Comparator
                    .comparingInt((HorizontalOffset offset) -> offset.dx() * offset.dx() + offset.dz() * offset.dz())
                    .thenComparingInt(HorizontalOffset::dz)
                    .thenComparingInt(HorizontalOffset::dx));
            offsets.addAll(ringOffsets);
        }
        return List.copyOf(offsets);
    }

    /** Offset in blocks on the x and z axes from the spawn column. */
    public record HorizontalOffset(int dx, int dz) {
    }
}
