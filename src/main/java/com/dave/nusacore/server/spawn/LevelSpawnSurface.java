package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.spawn.SpawnSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

/**
 * Adapts a live server level to the pure {@link SpawnSurface} view.
 *
 * <p>Reads are limited to already loaded chunks: {@link ServerLevel#hasChunk(int, int)} looks up existing
 * chunks and never generates one, and {@link SpawnSurface#isColumnLoaded} guards every block read.
 */
public final class LevelSpawnSurface implements SpawnSurface {
    private final ServerLevel level;

    public LevelSpawnSurface(ServerLevel level) {
        this.level = level;
    }

    @Override
    public BlockPos worldSpawn() {
        // The Overworld's own spawn data, not the effective respawn data: /setworldspawn run in another
        // dimension changes the respawn dimension, and /lobby must still target the Overworld spawn. The
        // border-adjusted copy matches what vanilla uses when it places a player at the world spawn.
        LevelData.RespawnData spawn = level.getLevelData().getRespawnData();
        return level.getWorldBorderAdjustedRespawnData(spawn).pos();
    }

    @Override
    public int minY() {
        return level.getMinY();
    }

    @Override
    public int maxY() {
        return level.getMaxY();
    }

    @Override
    public boolean isColumnLoaded(int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    @Override
    public BlockStateInfo blockAt(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        return new BlockStateInfo(
                state.isCollisionShapeFullBlock(level, pos),
                !state.getCollisionShape(level, pos).isEmpty(),
                !state.getFluidState().isEmpty(),
                isHazard(state));
    }

    /**
     * True for states that damage a player standing in them or on them.
     *
     * <p>The list is the audit of every block that can hurt a player while still passing the shape, collision,
     * and fluid rules of {@link com.dave.nusacore.core.spawn.SafeDestination}: blocks with no collision shape at
     * all ({@link Blocks#WITHER_ROSE}, {@link Blocks#FIRE}), blocks that burn on contact ({@link
     * Blocks#MAGMA_BLOCK}), and portals, which have no shape either and would move the player away from the
     * chosen destination. Blocks that already fail those rules are not repeated: lava and cauldrons are liquid
     * or not a full cube, and a thin plant, cobweb, or spike is not a floor. Blocks that only trap, slow, or hurt
     * on a multi-block fall are also not hazards, because the destination is never dropped onto.
     *
     * <p>The state is checked rather than just the block, so an unlit campfire or candle stays usable. The
     * {@code minecraft:fire} tag is matched as well, for a modded block that burns like fire; vanilla fire and
     * soul fire are matched by block because a tag only resolves against the datapack registry, which a bare
     * unit test does not populate.
     */
    public static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(BlockTags.FIRE)
                || isLit(state, Blocks.CAMPFIRE)
                || isLit(state, Blocks.SOUL_CAMPFIRE)
                || isCandleLit(state)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.END_GATEWAY);
    }

    /** True for a candle or candle cake, plain or dyed, whose flame is currently lit. */
    private static boolean isCandleLit(BlockState state) {
        return state.hasProperty(CandleBlock.LIT) && state.getValue(CandleBlock.LIT);
    }

    /** True when {@code state} is a lit campfire, the only campfire state that damages. */
    private static boolean isLit(BlockState state, Block block) {
        return state.is(block) && state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT);
    }
}
