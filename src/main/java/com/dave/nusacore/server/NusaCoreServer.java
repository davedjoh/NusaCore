package com.dave.nusacore.server;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.spawn.LobbyService;
import com.dave.nusacore.server.spawn.LobbyCommand;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

/** Wires the server gameplay features. Client code is never referenced here. */
public final class NusaCoreServer {
    private static final SpawnConfig SPAWN_CONFIG = SpawnConfig.DEFAULT;

    private NusaCoreServer() {
    }

    /** Registers gameplay commands. Called once from the server entrypoint. */
    public static void registerCommands() {
        LobbyCommand lobbyCommand = new LobbyCommand(new LobbyService(SPAWN_CONFIG));
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> registerLobby(dispatcher, lobbyCommand));
    }

    /**
     * Adds {@code /lobby} unless the dispatcher already has it. Another mod's command is never replaced, which
     * keeps NusaCore from breaking a server that already provides a lobby command.
     */
    static void registerLobby(CommandDispatcher<CommandSourceStack> dispatcher, LobbyCommand lobbyCommand) {
        if (dispatcher.getRoot().getChild(LobbyCommand.NAME) != null) {
            return;
        }
        dispatcher.register(lobbyCommand.builder());
    }
}
