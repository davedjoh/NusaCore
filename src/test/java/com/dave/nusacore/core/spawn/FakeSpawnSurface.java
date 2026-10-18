package com.dave.nusacore.core.spawn;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;

/** In-memory {@link SpawnSurface} for tests: a flat world by default, with per-position overrides. */
public final class FakeSpawnSurface implements SpawnSurface {
    private final Map<BlockPos, BlockStateInfo> blocks = new HashMap<>();
    private BlockPos spawn;
    private final int minY;
    private final int maxY;
    private final boolean loaded;
    private int blockReads;

    public FakeSpawnSurface(BlockPos spawn, int minY, int maxY, boolean loaded) {
        this.spawn = spawn;
        this.minY = minY;
        this.maxY = maxY;
        this.loaded = loaded;
    }

    public static FakeSpawnSurface flat(BlockPos spawn) {
        return new FakeSpawnSurface(spawn, -64, 319, true);
    }

    public FakeSpawnSurface floorAt(int y) {
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                blocks.put(new BlockPos(x, y, z), BlockStateInfo.SOLID_FLOOR);
            }
        }
        return this;
    }

    public FakeSpawnSurface set(int x, int y, int z, BlockStateInfo state) {
        blocks.put(new BlockPos(x, y, z), state);
        return this;
    }

    public FakeSpawnSurface setSpawn(BlockPos newSpawn) {
        this.spawn = newSpawn;
        return this;
    }

    public int blockReads() {
        return blockReads;
    }

    @Override
    public BlockPos worldSpawn() {
        return spawn;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public boolean isColumnLoaded(int x, int z) {
        return loaded;
    }

    @Override
    public BlockStateInfo blockAt(int x, int y, int z) {
        blockReads++;
        return blocks.getOrDefault(new BlockPos(x, y, z), BlockStateInfo.AIR);
    }
}
