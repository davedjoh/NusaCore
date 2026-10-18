package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.permission.PermissionNodes;
import com.dave.nusacore.core.spawn.LobbyService;

import com.mojang.brigadier.Command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The {@code /lobby} request path: who may run it, what it resolves, and whether the player moves.
 *
 * <p>The steps run in a fixed order and each one can end the request: a non-player source, a missing permission
 * node, a missing Overworld, no safe destination, and only then the teleport. Every failure reports one message
 * from the shared message set and leaves the player exactly where they were.
 *
 * <p>The player is a type parameter rather than a fixed {@code ServerPlayer} so this path can be exercised
 * without a running server: production instantiates {@code LobbyRequest<ServerPlayer>}, while a test passes a
 * token player and records what the actions receive. A real {@code ServerPlayer} cannot be built without a live
 * server, and this seam keeps every game call behind {@link Actions} instead of adding a mocking dependency.
 *
 * <p>All methods run on the server thread, because they read live level data and move a player.
 */
public final class LobbyRequest<P> {
    private final LobbyService lobbyService;
    private final Predicate<CommandSourceStack> permissionCheck;

    /**
     * @param lobbyService resolves the destination from the Overworld view
     * @param permissionCheck the {@code /lobby} gate, given the command source
     */
    public LobbyRequest(LobbyService lobbyService, Predicate<CommandSourceStack> permissionCheck) {
        this.lobbyService = Objects.requireNonNull(lobbyService, "lobbyService");
        this.permissionCheck = Objects.requireNonNull(permissionCheck, "permissionCheck");
    }

    /**
     * The production gate: the NusaCore permission node, with the API fallback that denies when no provider
     * answers. Kept in one place so the gate that ships is the gate that is tested.
     */
    public static <P> LobbyRequest<P> withPermissionNode(LobbyService lobbyService) {
        return new LobbyRequest<>(
                lobbyService, source -> PermissionNodes.check(source, PermissionNodes.SPAWN_LOBBY));
    }

    /**
     * Runs one request.
     *
     * @param player the executing player, or {@code null} for console and command blocks
     * @param actions the server operations for this execution
     * @return {@link Command#SINGLE_SUCCESS} only when the player was moved, otherwise {@code 0}
     */
    public int execute(P player, Actions<P> actions) {
        if (player == null) {
            actions.sendFailure(Messages.playersOnly());
            return 0;
        }
        if (!permissionCheck.test(actions.source())) {
            actions.sendFailure(PermissionNodes.denied());
            return 0;
        }

        LobbyTarget<P> target = actions.overworld();
        LobbyService.Outcome outcome = lobbyService.resolve(target == null ? null : target.surface());
        if (!outcome.ready()) {
            actions.sendFailure(LobbyService.message(outcome));
            return 0;
        }

        target.teleport(player, outcome.destination());
        actions.sendSuccess(LobbyService.message(outcome));
        return Command.SINGLE_SUCCESS;
    }

    /** The game operations one request needs, so the request itself holds no live game state. */
    public interface Actions<P> {

        /** The command source being answered, used for the permission gate and nothing else. */
        CommandSourceStack source();

        /** The Overworld target, or {@code null} when the server has no Overworld. */
        LobbyTarget<P> overworld();

        /** Reports a failure to the requester. */
        void sendFailure(Component message);

        /** Reports success to the requester. */
        void sendSuccess(Component message);
    }
}
