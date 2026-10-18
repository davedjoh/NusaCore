package com.dave.nusacore.core.spawn;

import java.util.Optional;

import com.dave.nusacore.core.spawn.SafeSpawnSearch.HorizontalOffset;
import com.dave.nusacore.core.spawn.SpawnSurface.BlockStateInfo;

import net.minecraft.core.BlockPos;

/**
 * Pure safe-destination logic for {@code /lobby}: given the world spawn and a {@link SpawnSurface}, returns a
 * standing position near the spawn that is safe for a player.
 *
 * <p>Inside each column the scan starts at the spawn feet block and goes down, so a raised spawn finds the
 * surface below it and a spawn inside terrain finds the first pocket with two blocks of clearance. A ground
 * block is accepted when its collision box fills the block and it is neither liquid nor hazardous, and the two
 * blocks the player body occupies are free of collision, liquid, and hazards. A full cube floor keeps the feet
 * plane exactly at the block top, which is what mob spawning and spawn protection use. Columns that are not
 * loaded are skipped so the search never forces chunk work.
 */
public final class SafeDestination {
    /** Clearance above the ground for a player body. Keeps head room and blocks enclosed tunnels. */
    public static final int BODY_HEIGHT = 2;

    private SafeDestination() {
    }

    /**
     * Finds the first acceptable destination in {@link SafeSpawnSearch} order.
     *
     * @param surface the read-only world view
     * @param spawn the world spawn position from the level data
     * @param search bounded search order
     * @return the ground block under the destination, empty when nothing safe is within the search bounds
     */
    public static Optional<BlockPos> find(SpawnSurface surface, BlockPos spawn, SafeSpawnSearch search) {
        if (spawn.getY() < surface.minY() || spawn.getY() > surface.maxY()) {
            return Optional.empty();
        }
        for (HorizontalOffset offset : search.horizontalOffsets()) {
            int x = spawn.getX() + offset.dx();
            int z = spawn.getZ() + offset.dz();
            if (!surface.isColumnLoaded(x, z)) {
                continue;
            }
            int lowest = spawn.getY() - search.scanDepth() + 1;
            for (int y = spawn.getY(); y >= lowest && y >= surface.minY(); y--) {
                BlockPos ground = new BlockPos(x, y, z);
                if (isSafeStandingPosition(surface, ground)) {
                    return Optional.of(ground);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the destination position: the block above the ground's top face. For a full cube ground this is
     * also the block position used by mob spawning on that ground.
     */
    public static BlockPos plankPosition(BlockPos ground) {
        return ground.above();
    }

    /** True when a player can stand on top of {@code ground} without harm. */
    public static boolean isSafeStandingPosition(SpawnSurface surface, BlockPos ground) {
        if (ground.getY() < surface.minY() || ground.getY() > surface.maxY()) {
            return false;
        }
        BlockStateInfo floor = surface.blockAt(ground.getX(), ground.getY(), ground.getZ());
        if (!floor.fullCollisionShape() || floor.liquid() || floor.hazard()) {
            return false;
        }
        for (int i = 1; i <= BODY_HEIGHT; i++) {
            int y = ground.getY() + i;
            if (y > surface.maxY()) {
                return false;
            }
            BlockStateInfo body = surface.blockAt(ground.getX(), y, ground.getZ());
            if (body.collides() || body.liquid() || body.hazard()) {
                return false;
            }
        }
        return true;
    }
}
