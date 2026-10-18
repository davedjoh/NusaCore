package com.dave.nusacore.server;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.spawn.LobbyService;
import com.dave.nusacore.server.spawn.LobbyCommand;
import com.dave.nusacore.server.spawn.LobbyCountdownRuntime;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Wires the server gameplay features. Client code is never referenced here. */
public final class NusaCoreServer {
    private static final SpawnConfig SPAWN_CONFIG = SpawnConfig.DEFAULT;

    /** The lobby countdown, created once so its pending state lives for the whole server session. */
    private static LobbyCountdownRuntime lobbyCountdown;

    private NusaCoreServer() {
    }

    /**
     * Registers gameplay commands and the countdown tick and lifecycle hooks. Called once from the server
     * entrypoint.
     */
    public static void registerCommands() {
        LobbyService lobbyService = new LobbyService(SPAWN_CONFIG);
        LobbyCountdownRuntime countdown = LobbyCountdownRuntime.install(lobbyService);
        lobbyCountdown = countdown;
        LobbyCommand<ServerPlayer> lobbyCommand =
                new LobbyCommand<>(countdown.service(), CommandSourceStack::getPlayer);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> registerLobby(dispatcher, lobbyCommand));
    }

    /**
     * Adds {@code /lobby} unless the dispatcher already has it. Another mod's command is never replaced, which
     * keeps NusaCore from breaking a server that already provides a lobby command.
     */
    static void registerLobby(CommandDispatcher<CommandSourceStack> dispatcher, LobbyCommand<?> lobbyCommand) {
        if (dispatcher.getRoot().getChild(LobbyCommand.NAME) != null) {
            return;
        }
        dispatcher.register(lobbyCommand.builder());
    }

    /** The countdown runtime for the current session, or {@code null} before the entrypoint ran. */
    public static LobbyCountdownRuntime lobbyCountdown() {
        return lobbyCountdown;
    }
}
