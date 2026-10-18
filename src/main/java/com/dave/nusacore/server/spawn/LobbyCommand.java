package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.permission.PermissionNodes;
import com.dave.nusacore.core.spawn.LobbyService;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers {@code /lobby}.
 *
 * <p>The command is registered for every source so a denied attempt can answer with the shared denial message
 * from {@link com.dave.nusacore.core.message.Messages}. The decision itself lives in {@link LobbyRequest}, which
 * is gated by {@link PermissionNodes#SPAWN_LOBBY} with a denied fallback, so a player without the node never
 * moves. The source must be a player; console and command blocks cannot teleport.
 *
 * <p>The Overworld comes from the server and is wrapped in {@link LevelLobbyTarget}, which both reads the view
 * and performs the move, so a player in another dimension is transferred to the Overworld spawn. A failed
 * attempt sends only a message and leaves the player where they were.
 *
 * <p>The command is not registered when the dispatcher already has {@code /lobby}, so a command from another
 * mod wins instead of being replaced.
 */
public final class LobbyCommand {
    public static final String NAME = "lobby";

    private final LobbyRequest<ServerPlayer> request;

    public LobbyCommand(LobbyService lobbyService) {
        this.request = LobbyRequest.withPermissionNode(lobbyService);
    }

    /** The command tree, so its shape can be inspected without a dispatcher or a running game. */
    public LiteralArgumentBuilder<CommandSourceStack> builder() {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(NAME).executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        return request.execute(source.getPlayer(), new SourceActions(source));
    }

    /** The real server operations for one execution of {@code /lobby}. */
    private record SourceActions(CommandSourceStack source) implements LobbyRequest.Actions<ServerPlayer> {

        @Override
        public LobbyTarget<ServerPlayer> overworld() {
            MinecraftServer server = source.getServer();
            if (server == null) {
                return null;
            }
            ServerLevel overworld = server.overworld();
            return overworld == null ? null : new LevelLobbyTarget(overworld);
        }

        @Override
        public void sendFailure(Component message) {
            source.sendFailure(message);
        }

        @Override
        public void sendSuccess(Component message) {
            source.sendSuccess(() -> message, false);
        }
    }
}
