package com.dave.nusacore.server.spawn;

import java.util.List;
import java.util.Optional;

import com.dave.nusacore.core.spawn.FakeSpawnSurface;
import com.dave.nusacore.core.spawn.SafeDestination;
import com.dave.nusacore.core.spawn.SafeSpawnSearch;
import com.dave.nusacore.core.spawn.SpawnSurface.BlockStateInfo;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the hazard classification in {@link LevelSpawnSurface} against the real 26.2 block registry.
 *
 * <p>The registries are bootstrapped once for this class, which is what makes {@link Blocks} and block state
 * properties readable in a plain test. Each case feeds the block's real collision and fluid data into the pure
 * {@link SafeDestination} rules, so the rejection is proven to come from the hazard flag rather than from a shape
 * that would have been rejected anyway.
 */
class LevelSpawnSurfaceTest {
    private static final BlockPos SPAWN = new BlockPos(0, 70, 0);
    private static final SafeSpawnSearch SEARCH = new SafeSpawnSearch(1, 2);

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void witherRoseIsAHazard() {
        assertTrue(LevelSpawnSurface.isHazard(Blocks.WITHER_ROSE.defaultBlockState()));
    }

    @Test
    void aWitherRoseInTheFeetBlockRejectsTheSpawnColumn() {
        FakeSpawnSurface surface = flatFloor();
        surface.set(0, 70, 0, info(Blocks.WITHER_ROSE.defaultBlockState()));

        assertFalse(SafeDestination.isSafeStandingPosition(surface, new BlockPos(0, 69, 0)),
                "a player standing in a wither rose takes damage");
        assertEquals(Optional.of(new BlockPos(0, 69, -1)), SafeDestination.find(surface, SPAWN, SEARCH),
                "the nearest normally shaped column is accepted instead");
    }

    @Test
    void aWitherRoseInTheHeadBlockRejectsTheSpawnColumn() {
        FakeSpawnSurface surface = flatFloor();
        surface.set(0, 71, 0, info(Blocks.WITHER_ROSE.defaultBlockState()));

        assertFalse(SafeDestination.isSafeStandingPosition(surface, new BlockPos(0, 69, 0)),
                "a wither rose occupies the head block and still damages the player");
        assertEquals(Optional.of(new BlockPos(0, 69, -1)), SafeDestination.find(surface, SPAWN, SEARCH));
    }

    @Test
    void aNormalCandidateIsStillAccepted() {
        FakeSpawnSurface surface = flatFloor();

        assertEquals(Optional.of(new BlockPos(0, 69, 0)), SafeDestination.find(surface, SPAWN, SEARCH));
    }

    @Test
    void theWitherRoseRejectionIsCausedByTheHazardFlagAndNotByItsShape() {
        BlockState rose = Blocks.WITHER_ROSE.defaultBlockState();
        assertTrue(rose.getCollisionShape(null, BlockPos.ZERO).isEmpty(),
                "a wither rose has no collision shape, so shape and fluid checks alone would accept it");

        FakeSpawnSurface surface = flatFloor();
        surface.set(0, 70, 0, new BlockStateInfo(false, false, false, false));
        assertEquals(Optional.of(new BlockPos(0, 69, 0)), SafeDestination.find(surface, SPAWN, SEARCH),
                "with the hazard flag cleared the rose would be treated as open air");
    }

    @Test
    void theAuditedDamagingBlocksAreFlagged() {
        List<BlockState> hazards = List.of(
                Blocks.FIRE.defaultBlockState(),
                Blocks.SOUL_FIRE.defaultBlockState(),
                Blocks.CAMPFIRE.defaultBlockState(),
                Blocks.SOUL_CAMPFIRE.defaultBlockState(),
                Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true),
                Blocks.CANDLE_CAKE.defaultBlockState().setValue(CandleBlock.LIT, true),
                Blocks.DYED_CANDLE.red().defaultBlockState().setValue(CandleBlock.LIT, true),
                Blocks.MAGMA_BLOCK.defaultBlockState(),
                Blocks.CACTUS.defaultBlockState(),
                Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
                Blocks.POWDER_SNOW.defaultBlockState(),
                Blocks.WITHER_ROSE.defaultBlockState(),
                Blocks.NETHER_PORTAL.defaultBlockState(),
                Blocks.END_PORTAL.defaultBlockState(),
                Blocks.END_GATEWAY.defaultBlockState());

        for (BlockState state : hazards) {
            assertTrue(LevelSpawnSurface.isHazard(state), "expected a hazard: " + state);
        }
    }

    @Test
    void ordinaryBlocksAndUnlitFireAreNotFlagged() {
        List<BlockState> safe = List.of(
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
                Blocks.OAK_PLANKS.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.TALL_GRASS.defaultBlockState(),
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false),
                Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, false),
                Blocks.COBBLESTONE.defaultBlockState());

        for (BlockState state : safe) {
            assertFalse(LevelSpawnSurface.isHazard(state), "expected no hazard: " + state);
        }
    }

    /** A 5x5 stone floor at y=69, wide enough for a radius-1 search. */
    private static FakeSpawnSurface flatFloor() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(SPAWN);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                surface.set(x, 69, z, info(Blocks.STONE.defaultBlockState()));
            }
        }
        return surface;
    }

    /** The block info the live surface reports for a state: real shape, real fluid, real hazard classification. */
    private static BlockStateInfo info(BlockState state) {
        return new BlockStateInfo(
                state.isCollisionShapeFullBlock(null, BlockPos.ZERO),
                !state.getCollisionShape(null, BlockPos.ZERO).isEmpty(),
                !state.getFluidState().isEmpty(),
                LevelSpawnSurface.isHazard(state));
    }
}
