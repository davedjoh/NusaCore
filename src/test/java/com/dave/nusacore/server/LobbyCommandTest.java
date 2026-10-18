package com.dave.nusacore.server;

import java.util.ArrayList;
import java.util.List;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.spawn.LobbyService;
import com.dave.nusacore.server.spawn.FakeLobbyPlayer;
import com.dave.nusacore.server.spawn.FakeLobbyPlayer.Player;
import com.dave.nusacore.server.spawn.LobbyCommand;
import com.dave.nusacore.server.spawn.LobbyCountdownService;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LobbyCommandTest {
    private final FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
    private final LobbyCountdownService<Player> countdown =
            LobbyCountdownService.create(new LobbyService(SpawnConfig.DEFAULT), adapter);
    private final LobbyCommand<Player> lobbyCommand =
            new LobbyCommand<>(countdown, source -> source.getPlayer() == null ? null : adapter.player());

    @Test
    void commandIsRegisteredWithTheExpectedNameAndNoArguments() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        NusaCoreServer.registerLobby(dispatcher, lobbyCommand);

        var lobby = dispatcher.getRoot().getChild(LobbyCommand.NAME);
        assertNotNull(lobby, "/lobby is registered");
        assertNotNull(lobby.getCommand(), "/lobby has an executable action");
        assertTrue(lobby.getChildren().isEmpty(), "/lobby takes no arguments and has no subcommands");
    }

    @Test
    void anExistingLobbyCommandIsNotReplaced() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        var existing = dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal(LobbyCommand.NAME)
                .executes(context -> 7));

        NusaCoreServer.registerLobby(dispatcher, lobbyCommand);

        assertSame(existing, dispatcher.getRoot().getChild(LobbyCommand.NAME));
        assertEquals(1, dispatcher.getRoot().getChildren().size());
    }

    @Test
    void nonPlayerSourceFailsWithoutStartingACountdown() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NusaCoreServer.registerLobby(dispatcher, lobbyCommand);
        RecordingSource console = new RecordingSource();

        int result = dispatcher.execute(LobbyCommand.NAME, source(console));

        assertEquals(0, result, "a failed command reports no success");
        assertEquals(List.of(Messages.playersOnly().getString()), console.failures,
                "console gets the shared player-only message once");
        assertEquals(List.of(), adapter.messages(), "no countdown layer reports the non-player path");
        assertSingleFailure(adapter, console, Messages.playersOnly());
        assertEquals(0, countdown.pendingCount(), "console cannot start a countdown");
    }

    @Test
    void aPermittedPlayerStartsTheCountdownWithoutAFailure() throws Exception {
        Fixture fixture = fixture(FakeLobbyPlayer.onSafeFloor());
        RecordingSource source = new RecordingSource();

        int result = fixture.execute(source);

        assertEquals(Command.SINGLE_SUCCESS, result);
        assertEquals(List.of(), source.failures, "a successful start reports no failure");
        assertEquals(List.of(Messages.lobbyCountdownStarted(Messages.LOBBY_COUNTDOWN_SECONDS).getString()),
                fixture.adapter().messages(), "the start line is sent once by the countdown");
        assertEquals(1, fixture.countdown().pendingCount());
    }

    @Test
    void aDeniedPlayerGetsExactlyOneFailure() throws Exception {
        Fixture fixture = fixture(FakeLobbyPlayer.onSafeFloor().deny());
        RecordingSource source = new RecordingSource();

        int result = fixture.execute(source);

        assertEquals(0, result);
        assertSingleFailure(fixture.adapter(), source, Messages.noPermission());
        assertEquals(0, fixture.countdown().pendingCount(), "a denied request starts no countdown");
    }

    @Test
    void aSecondRequestWhilePendingGetsExactlyOneFailure() throws Exception {
        Fixture fixture = fixture(FakeLobbyPlayer.onSafeFloor());
        assertEquals(Command.SINGLE_SUCCESS, fixture.execute(new RecordingSource()), "the first request starts");
        RecordingSource source = new RecordingSource();

        int result = fixture.execute(source);

        assertEquals(0, result);
        assertSingleFailure(fixture.adapter(), source, Messages.lobbyAlreadyPending());
        assertEquals(1, fixture.countdown().pendingCount(), "the refused request did not add a countdown");
    }

    @Test
    void aMissingOverworldGetsExactlyOneFailure() throws Exception {
        Fixture fixture = fixture(FakeLobbyPlayer.withoutOverworld());
        RecordingSource source = new RecordingSource();

        int result = fixture.execute(source);

        assertEquals(0, result);
        assertSingleFailure(fixture.adapter(), source, Messages.lobbyNoOverworld());
        assertEquals(0, fixture.countdown().pendingCount());
    }

    @Test
    void anUnsafeDestinationGetsExactlyOneFailure() throws Exception {
        Fixture fixture = fixture(FakeLobbyPlayer.onUnsafeFloor());
        RecordingSource source = new RecordingSource();

        int result = fixture.execute(source);

        assertEquals(0, result);
        assertSingleFailure(fixture.adapter(), source, Messages.lobbyNoSafeDestination());
        assertEquals(0, fixture.countdown().pendingCount());
    }

    @Test
    void anUnavailablePlayerGetsExactlyOneFailure() throws Exception {
        Fixture fixture = fixture(FakeLobbyPlayer.onSafeFloor().disconnect());
        RecordingSource source = new RecordingSource();

        int result = fixture.execute(source);

        assertEquals(0, result);
        assertSingleFailure(fixture.adapter(), source, Messages.lobbyCountdownCancelledDisconnected());
        assertEquals(0, fixture.countdown().pendingCount());
    }

    @Test
    void theCommandOverTheFakeCountdownHasTheExpectedShape() {
        var node = lobbyCommand.builder().build();

        assertEquals(LobbyCommand.NAME, node.getName());
        assertNotNull(node.getCommand());
        assertTrue(node.getChildren().isEmpty());
    }

    /** A countdown, a command that always resolves the fixture's player, and a dispatcher holding that command. */
    private record Fixture(
            FakeLobbyPlayer adapter,
            LobbyCountdownService<Player> countdown,
            CommandDispatcher<CommandSourceStack> dispatcher) {

        int execute(RecordingSource source) throws Exception {
            return dispatcher.execute(LobbyCommand.NAME, LobbyCommandTest.source(source));
        }
    }

    private static Fixture fixture(FakeLobbyPlayer adapter) {
        LobbyCountdownService<Player> countdown =
                LobbyCountdownService.create(new LobbyService(SpawnConfig.DEFAULT), adapter);
        LobbyCommand<Player> command = new LobbyCommand<>(countdown, source -> adapter.player());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NusaCoreServer.registerLobby(dispatcher, command);
        return new Fixture(adapter, countdown, dispatcher);
    }

    /**
     * Asserts the player received the message exactly once across both reporting layers, so a failure reported by
     * the service and again by the command counts twice and fails.
     */
    private static void assertSingleFailure(FakeLobbyPlayer adapter, RecordingSource source, Component message) {
        String text = message.getString();
        long sentToPlayer = adapter.messages().stream().filter(text::equals).count();
        long sentToSource = source.failures.stream().filter(text::equals).count();
        assertEquals(1, sentToPlayer + sentToSource,
                () -> "expected exactly one \"" + text + "\" message, got player=" + sentToPlayer
                        + " source=" + sentToSource);
    }

    private static CommandSourceStack source(CommandSource commandSource) {
        return new CommandSourceStack(
                commandSource,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                null,
                "test",
                Component.literal("test"),
                null,
                null);
    }

    /** Records the messages a command source receives. */
    private static final class RecordingSource implements CommandSource {
        private final List<String> failures = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            failures.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
