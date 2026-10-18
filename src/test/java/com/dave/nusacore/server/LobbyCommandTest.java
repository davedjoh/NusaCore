package com.dave.nusacore.server;

import java.util.ArrayList;
import java.util.List;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.spawn.LobbyService;
import com.dave.nusacore.server.spawn.LobbyCommand;

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
    private final LobbyCommand lobbyCommand = new LobbyCommand(new LobbyService(SpawnConfig.DEFAULT));

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
    void nonPlayerSourceFailsWithoutTeleporting() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NusaCoreServer.registerLobby(dispatcher, lobbyCommand);
        RecordingSource console = new RecordingSource();

        int result = dispatcher.execute(LobbyCommand.NAME, source(console));

        assertEquals(0, result, "a failed command reports no success");
        assertTrue(console.failures.stream().anyMatch(text -> text.contains("Only players")),
                "console gets the shared player-only message");
    }

    @Test
    void theCommandTreeCanBeBuiltWithoutARunningServer() {
        assertDoesNotThrow(() -> lobbyCommand.builder());
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
