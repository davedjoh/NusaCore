package com.dave.nusacore.core.spawn;

import java.util.Optional;

import com.dave.nusacore.core.spawn.SpawnSurface.BlockStateInfo;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeDestinationTest {
    private static final SafeSpawnSearch SEARCH = new SafeSpawnSearch(2, 4);
    private static final BlockStateInfo WATER = new BlockStateInfo(true, true, true, false);
    private static final BlockStateInfo LAVA = new BlockStateInfo(true, true, true, true);
    private static final BlockStateInfo FIRE = new BlockStateInfo(false, false, false, true);
    private static final BlockStateInfo PLANT = new BlockStateInfo(false, false, false, false);
    private static final BlockStateInfo SLAB = new BlockStateInfo(false, true, false, false);

    @Test
    void usesTheSpawnColumnWhenTheSurfaceIsSafe() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(63);

        assertEquals(Optional.of(new BlockPos(0, 63, 0)), SafeDestination.find(surface, surface.worldSpawn(), SEARCH));
    }

    @Test
    void aRaisedSpawnDropsDownToTheSurfaceBelowIt() {
        BlockPos spawn = new BlockPos(0, 66, 0);
        FakeSpawnSurface surface = FakeSpawnSurface.flat(spawn).floorAt(63);

        assertEquals(Optional.of(new BlockPos(0, 63, 0)), SafeDestination.find(surface, spawn, SEARCH));
    }

    @Test
    void searchesOutwardWhenTheSpawnColumnIsFilled() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(63);
        for (int y = 60; y <= 70; y++) {
            surface.set(0, y, 0, BlockStateInfo.SOLID_FLOOR);
        }

        Optional<BlockPos> ground = SafeDestination.find(surface, surface.worldSpawn(), SEARCH);

        assertTrue(ground.isPresent());
        BlockPos pos = ground.get();
        assertEquals(1, Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ())), "nearest free ring is chosen");
        assertEquals(63, pos.getY());
    }

    @Test
    void skipsWaterLavaAndHazardousGround() {
        BlockPos spawn = new BlockPos(0, 64, 0);
        FakeSpawnSurface water = FakeSpawnSurface.flat(spawn).floorAt(63);
        water.set(0, 63, 0, WATER);
        FakeSpawnSurface lava = FakeSpawnSurface.flat(spawn).floorAt(63);
        lava.set(0, 63, 0, LAVA);
        FakeSpawnSurface fire = FakeSpawnSurface.flat(spawn).floorAt(63);
        fire.set(0, 64, 0, FIRE);

        assertNotEquals(Optional.of(new BlockPos(0, 63, 0)), SafeDestination.find(water, spawn, SEARCH));
        assertNotEquals(Optional.of(new BlockPos(0, 63, 0)), SafeDestination.find(lava, spawn, SEARCH));
        assertNotEquals(Optional.of(new BlockPos(0, 63, 0)), SafeDestination.find(fire, spawn, SEARCH));
    }

    @Test
    void findsThePocketBelowAFilledSpawnColumn() {
        BlockPos spawn = new BlockPos(0, 64, 0);
        FakeSpawnSurface surface = FakeSpawnSurface.flat(spawn).floorAt(60);
        // Solid from y=63 up, a two block cavity at y=61 and y=62, floor at y=60.
        for (int y = 63; y <= 66; y++) {
            surface.set(0, y, 0, BlockStateInfo.SOLID_FLOOR);
        }

        assertEquals(Optional.of(new BlockPos(0, 60, 0)),
                SafeDestination.find(surface, spawn, new SafeSpawnSearch(0, 5)));
    }

    @Test
    void requiresHeadRoomForTheWholeBody() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(63);
        BlockPos ground = new BlockPos(0, 63, 0);

        assertTrue(SafeDestination.isSafeStandingPosition(surface, ground));

        surface.set(0, 64, 0, BlockStateInfo.SOLID_FLOOR);
        assertFalse(SafeDestination.isSafeStandingPosition(surface, ground), "feet block must be free");

        surface.set(0, 64, 0, BlockStateInfo.AIR);
        surface.set(0, 65, 0, BlockStateInfo.SOLID_FLOOR);
        assertFalse(SafeDestination.isSafeStandingPosition(surface, ground), "head block must be free");

        surface.set(0, 65, 0, BlockStateInfo.AIR);
        assertTrue(SafeDestination.isSafeStandingPosition(surface, ground));
    }

    @Test
    void thinPlantsAreNotUsedAsFloor() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(63);
        surface.set(0, 63, 0, PLANT);

        assertFalse(SafeDestination.isSafeStandingPosition(surface, new BlockPos(0, 63, 0)));
    }

    @Test
    void aPartialFloorIsNotUsedBecauseTheFeetPlaneWouldBeWrong() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(63);
        surface.set(0, 63, 0, SLAB);

        assertFalse(SafeDestination.isSafeStandingPosition(surface, new BlockPos(0, 63, 0)));
    }

    @Test
    void nothingSafeInRangeIsReportedAsEmpty() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0));

        assertTrue(SafeDestination.find(surface, surface.worldSpawn(), SEARCH).isEmpty());
    }

    @Test
    void respectsTheBoundedScanDepth() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(60);

        // Depth 5 inspects y=64 down to y=60; depth 4 stops at y=61 and never reaches the floor.
        assertEquals(Optional.of(new BlockPos(0, 60, 0)),
                SafeDestination.find(surface, surface.worldSpawn(), new SafeSpawnSearch(0, 5)));
        assertTrue(SafeDestination.find(surface, surface.worldSpawn(), new SafeSpawnSearch(0, 4)).isEmpty());
    }

    @Test
    void unloadedColumnsAreNeverRead() {
        FakeSpawnSurface surface = new FakeSpawnSurface(new BlockPos(0, 64, 0), -64, 319, false);

        assertTrue(SafeDestination.find(surface, surface.worldSpawn(), SEARCH).isEmpty());
        assertEquals(0, surface.blockReads(), "no block may be read from an unloaded column");
    }

    @Test
    void spawnOutsideTheWorldBoundsIsIgnored() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 500, 0)).floorAt(63);

        assertTrue(SafeDestination.find(surface, surface.worldSpawn(), SEARCH).isEmpty());
    }

    @Test
    void destinationIsOneBlockAboveTheGroundWithMatchingFeetHeight() {
        BlockPos ground = new BlockPos(12, 63, -5);
        BlockPos destination = SafeDestination.plankPosition(ground);

        assertEquals(new BlockPos(12, 64, -5), destination);
        assertEquals(64.0, SpawnSurface.feetY(ground));
        assertEquals(64.0, LobbyService.feetPosition(destination).y);
        assertEquals(12.5, LobbyService.feetPosition(destination).x);
        assertEquals(-4.5, LobbyService.feetPosition(destination).z);
    }
}
