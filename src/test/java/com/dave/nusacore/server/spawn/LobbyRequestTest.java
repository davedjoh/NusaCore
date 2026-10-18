package com.dave.nusacore.server.spawn;

import java.util.ArrayList;
import java.util.List;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.message.MessageText;
import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.spawn.FakeSpawnSurface;
import com.dave.nusacore.core.spawn.LobbyService;

import net.fabricmc.fabric.api.util.TriState;import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real {@code /lobby} request path, {@link LobbyRequest}, with a token player.
 *
 * <p>Everything under test is production code: the request order, the gate wiring in
 * {@link LobbyRequest#withPermissionNode}, the {@link LobbyService} resolution, and the shared failure and
 * success messages. The command source is a real {@link CommandSourceStack} without a server; the Overworld is a
 * {@link LobbyTarget} over an in-memory {@link FakeSpawnSurface}, which is the same seam
 * {@link LevelLobbyTarget} fills in production through {@code LevelSpawnSurface}.
 *
 * <p>A live {@code ServerPlayer} and a live {@code ServerLevel} cannot be constructed in a unit test, so the
 * actual dimension transfer in {@link LevelLobbyTarget} is not executed here. The tests prove that the request
 * moves the player to the resolved destination through the target it read, that it never moves on any failure,
 * and that the target is opened exactly once. See {@code docs/compatibility.md} for what remains unverified.
 */
class LobbyRequestTest {
    private static final SpawnConfig CONFIG = new SpawnConfig(2, 2);
    private static final BlockPos WORLD_SPAWN = new BlockPos(0, 70, 0);
    private static final TestPlayer PLAYER = TestPlayer.INSTANCE;

    @Test
    void aNonPlayerSourceIsRejectedBeforePermissionOrWorldWork() {
        Recorder recorder = new Recorder(overworldWithFloor());

        int result = productionRequest().execute(null, recorder);

        assertEquals(0, result, "a failed command reports no success");
        assertEquals(List.of(MessageText.resolve(Messages.PLAYERS_ONLY)), recorder.messages);
        assertFalse(recorder.targetOpened, "a non-player source must not touch the Overworld");
        assertEquals(List.of(), recorder.moves);
    }

    @Test
    void aPlayerWithoutTheNodeIsDeniedWithoutTouchingTheOverworld() {
        Recorder recorder = new Recorder(overworldWithFloor());

        // With no permission provider installed the API returns its default, and NusaCore denies it. That is
        // exactly how a server without LuckPerms resolves every check.
        int result = productionRequest().execute(PLAYER, recorder);

        assertEquals(0, result);
        assertEquals(List.of(MessageText.resolve(Messages.NO_PERMISSION)), recorder.messages);
        assertFalse(recorder.targetOpened, "a denied player must not reach the world");
        assertEquals(List.of(), recorder.moves, "a denied player must not move");
    }

    @Test
    void anExplicitlyDeniedGateStopsBeforeTheOverworld() {
        Recorder recorder = new Recorder(overworldWithFloor());

        int result = new LobbyRequest<TestPlayer>(new LobbyService(CONFIG), source -> false)
                .execute(PLAYER, recorder);

        assertEquals(0, result);
        assertEquals(List.of(MessageText.resolve(Messages.NO_PERMISSION)), recorder.messages);
        assertFalse(recorder.targetOpened);
        assertEquals(List.of(), recorder.moves);
    }

    @Test
    void aGrantFromAPermissionProviderAllowsTheMove() {
        Recorder recorder = new Recorder(overworldWithFloor());
        try {
            TestPermissionProvider.answer(TriState.TRUE);

            int result = productionRequest().execute(PLAYER, recorder);

            assertEquals(1, result, "the production gate follows the provider's answer");
            assertEquals(List.of(new Move(PLAYER, WORLD_SPAWN)), recorder.moves);
        } finally {
            TestPermissionProvider.clear();
        }
    }

    @Test
    void aDenialFromAPermissionProviderStopsTheMove() {
        Recorder recorder = new Recorder(overworldWithFloor());
        try {
            TestPermissionProvider.answer(TriState.FALSE);

            int result = productionRequest().execute(PLAYER, recorder);

            assertEquals(0, result);
            assertEquals(List.of(MessageText.resolve(Messages.NO_PERMISSION)), recorder.messages);
            assertFalse(recorder.targetOpened, "a provider denial must not reach the world");
            assertEquals(List.of(), recorder.moves);
        } finally {
            TestPermissionProvider.clear();
        }
    }

    @Test
    void aPermittedPlayerResolvesTheSpawnAndMoves() {
        Recorder recorder = new Recorder(overworldWithFloor());

        int result = new LobbyRequest<TestPlayer>(new LobbyService(CONFIG), source -> true)
                .execute(PLAYER, recorder);

        assertEquals(1, result);
        assertEquals(List.of(MessageText.resolve(Messages.LOBBY_SUCCESS)), recorder.messages,
                "one success message and no failure");
        assertEquals(List.of(MessageText.resolve(Messages.LOBBY_SUCCESS)), recorder.successes);
        assertEquals(List.of(), recorder.failures);
        assertEquals(List.of(new Move(PLAYER, WORLD_SPAWN)), recorder.moves,
                "the player moves to the plank position above the world spawn floor");
        assertEquals(1, recorder.targetOpenedCount, "the Overworld target is opened exactly once");
        assertEquals(1, recorder.views, "the Overworld view is read exactly once");
    }

    @Test
    void theMoveUsesThePositionReadFromTheTarget() {
        // A floor with a hole only under the spawn column: the search must move outwards, and the move must go
        // where the search landed rather than to the raw spawn.
        FakeSpawnSurface surface = FakeSpawnSurface.flat(WORLD_SPAWN).floorAt(69);
        for (int y = 65; y <= 72; y++) {
            surface.set(0, y, 0, com.dave.nusacore.core.spawn.SpawnSurface.BlockStateInfo.SOLID_FLOOR);
        }
        Recorder recorder = new Recorder(overworldOver(surface));

        int result = new LobbyRequest<TestPlayer>(new LobbyService(CONFIG), source -> true)
                .execute(PLAYER, recorder);

        assertEquals(1, result);
        assertEquals(1, recorder.moves.size());
        BlockPos destination = recorder.moves.get(0).destination();
        assertNotEquals(WORLD_SPAWN, destination, "a blocked spawn column is skipped");
        assertEquals(1, Math.max(Math.abs(destination.getX()), Math.abs(destination.getZ())),
                "the nearest safe ring is used");
        assertEquals(70, destination.getY(), "feet stay one block above the ground");
    }

    @Test
    void anUnsafeSpawnFailsWithoutMovingThePlayer() {
        Recorder recorder = new Recorder(overworldOver(FakeSpawnSurface.flat(WORLD_SPAWN)));

        int result = new LobbyRequest<TestPlayer>(new LobbyService(CONFIG), source -> true)
                .execute(PLAYER, recorder);

        assertEquals(0, result);
        assertEquals(List.of(MessageText.resolve(Messages.LOBBY_NO_SAFE_DESTINATION)), recorder.messages);
        assertEquals(List.of(), recorder.moves, "an unsafe spawn must not move the player");
    }

    @Test
    void anUnloadedSpawnAreaFailsWithoutMovingThePlayerOrReadingBlocks() {
        FakeSpawnSurface surface = new FakeSpawnSurface(WORLD_SPAWN, -64, 319, false).floorAt(69);
        Recorder recorder = new Recorder(overworldOver(surface));

        int result = new LobbyRequest<TestPlayer>(new LobbyService(CONFIG), source -> true)
                .execute(PLAYER, recorder);

        assertEquals(0, result);
        assertEquals(List.of(MessageText.resolve(Messages.LOBBY_NO_SAFE_DESTINATION)), recorder.messages);
        assertEquals(List.of(), recorder.moves);
        assertEquals(0, surface.blockReads(), "no block may be read from an unloaded column");
    }

    @Test
    void aMissingOverworldFailsWithoutMovingThePlayer() {
        Recorder recorder = new Recorder(null);

        int result = new LobbyRequest<TestPlayer>(new LobbyService(CONFIG), source -> true)
                .execute(PLAYER, recorder);

        assertEquals(0, result);
        assertEquals(List.of(MessageText.resolve(Messages.LOBBY_NO_OVERWORLD)), recorder.messages);
        assertEquals(List.of(), recorder.moves);
        assertEquals(0, recorder.targetOpenedCount);
    }

    private static LobbyRequest<TestPlayer> productionRequest() {
        return LobbyRequest.withPermissionNode(new LobbyService(CONFIG));
    }

    private static LobbyTarget<TestPlayer> overworldWithFloor() {
        return overworldOver(FakeSpawnSurface.flat(WORLD_SPAWN).floorAt(69));
    }

    private static LobbyTarget<TestPlayer> overworldOver(FakeSpawnSurface surface) {
        return new LobbyTarget<>() {
            @Override
            public com.dave.nusacore.core.spawn.SpawnSurface surface() {
                return surface;
            }

            @Override
            public void teleport(TestPlayer player, BlockPos destination) {
                throw new AssertionError("the recorder supplies its own move");
            }
        };
    }

    /** A token player. The request only checks that a player is present. */
    private enum TestPlayer {
        INSTANCE
    }

    /** One recorded player move. */
    private record Move(TestPlayer player, BlockPos destination) {
    }

    /**
     * The {@link LobbyRequest.Actions} the request runs against: hands back a target that reports the view read
     * as any surface would, and records the move.
     */
    private static final class Recorder implements LobbyRequest.Actions<TestPlayer> {
        private final LobbyTarget<TestPlayer> overworld;
        private final List<String> messages = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<String> successes = new ArrayList<>();
        private final List<Move> moves = new ArrayList<>();
        private boolean targetOpened;
        private int targetOpenedCount;
        private int views;

        private Recorder(LobbyTarget<TestPlayer> overworld) {
            this.overworld = overworld;
        }

        @Override
        public CommandSourceStack source() {
            return new CommandSourceStack(
                    null, Vec3.ZERO, Vec2.ZERO, null, null, "test", Component.literal("test"), null, null);
        }

        @Override
        public LobbyTarget<TestPlayer> overworld() {
            if (overworld == null) {
                return null;
            }
            targetOpened = true;
            targetOpenedCount++;
            return new LobbyTarget<>() {
                @Override
                public com.dave.nusacore.core.spawn.SpawnSurface surface() {
                    views++;
                    return overworld.surface();
                }

                @Override
                public void teleport(TestPlayer player, BlockPos destination) {
                    moves.add(new Move(player, destination));
                }
            };
        }

        @Override
        public void sendFailure(Component message) {
            failures.add(message.getString());
            messages.add(message.getString());
        }

        @Override
        public void sendSuccess(Component message) {
            successes.add(message.getString());
            messages.add(message.getString());
        }
    }
}
