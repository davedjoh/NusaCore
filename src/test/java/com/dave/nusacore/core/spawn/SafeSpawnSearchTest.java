package com.dave.nusacore.core.spawn;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeSpawnSearchTest {
    @Test
    void horizontalOffsetsStartAtSpawnAndStayInsideTheRadius() {
        List<SafeSpawnSearch.HorizontalOffset> offsets = new SafeSpawnSearch(2, 1).horizontalOffsets();

        assertEquals(new SafeSpawnSearch.HorizontalOffset(0, 0), offsets.getFirst());
        // Rings of radius 0, 1 and 2: 1 + 8 + 16 tiles.
        assertEquals(25, offsets.size());
        assertTrue(offsets.stream().allMatch(o -> Math.abs(o.dx()) <= 2 && Math.abs(o.dz()) <= 2));
    }

    @Test
    void horizontalOrderIsDeterministicAndNearestFirst() {
        List<SafeSpawnSearch.HorizontalOffset> first = new SafeSpawnSearch(2, 1).horizontalOffsets();
        List<SafeSpawnSearch.HorizontalOffset> second = new SafeSpawnSearch(2, 1).horizontalOffsets();

        assertEquals(first, second);
        int previousRing = -1;
        int previousDistance = -1;
        for (SafeSpawnSearch.HorizontalOffset offset : first) {
            int ring = Math.max(Math.abs(offset.dx()), Math.abs(offset.dz()));
            int distance = offset.dx() * offset.dx() + offset.dz() * offset.dz();
            assertTrue(ring >= previousRing, "rings must not go backwards");
            if (ring != previousRing) {
                previousDistance = -1;
            }
            assertTrue(distance >= previousDistance, "each ring lists the nearest tiles first");
            previousDistance = distance;
            previousRing = ring;
        }
    }

    @Test
    void zeroRadiusOnlyOffersTheSpawnColumn() {
        assertEquals(List.of(new SafeSpawnSearch.HorizontalOffset(0, 0)),
                new SafeSpawnSearch(0, 1).horizontalOffsets());
    }

    @Test
    void scanDepthIsKept() {
        assertEquals(3, new SafeSpawnSearch(1, 3).scanDepth());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SafeSpawnSearch(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SafeSpawnSearch(0, 0));
    }
}
