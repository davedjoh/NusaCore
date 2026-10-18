package com.dave.nusacore.core.spawn;

import net.minecraft.core.BlockPos;

/**
 * Everything the lobby feature needs from a world, so the choice of destination can be tested without a
 * running game.
 */
public interface SpawnSurface {

    /** The live world spawn, already adjusted for the world border. */
    BlockPos worldSpawn();

    /** The lowest Y that may hold a block. */
    int minY();

    /** The highest Y that may hold a block. */
    int maxY();

    /** True when the column is already loaded. Unloaded columns are never queried or loaded. */
    boolean isColumnLoaded(int x, int z);

    /** Block state at the position. Only call for loaded columns and in-range Y values. */
    BlockStateInfo blockAt(int x, int y, int z);

    /**
     * The smallest description of a block state that the safety rules need, so no game class leaks into them.
     *
     * @param fullCollisionShape true when the collision box fills the whole block, as a floor must
     * @param collides true when the state has any collision box
     * @param liquid true when the state holds water, lava, or another fluid
     * @param hazard true when standing in or on the state can damage a player
     */
    record BlockStateInfo(boolean fullCollisionShape, boolean collides, boolean liquid, boolean hazard) {
        public static final BlockStateInfo AIR = new BlockStateInfo(false, false, false, false);
        public static final BlockStateInfo SOLID_FLOOR = new BlockStateInfo(true, true, false, false);
    }

    /**
     * Feet height for a player standing on the top face of {@code ground}. Mob spawning uses the same rule, so a
     * chosen spot also sits inside the vanilla spawn protection radius.
     */
    static double feetY(BlockPos ground) {
        return ground.getY() + 1.0;
    }
}
