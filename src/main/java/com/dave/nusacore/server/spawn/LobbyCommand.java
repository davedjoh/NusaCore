package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.permission.PermissionNodes;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Function;

/**
 * Registers {@code /lobby}, which starts a countdown that ends in the teleport.
 *
 * <p>The command is registered for every source so a denied attempt can answer with the shared denial message
 * from {@link Messages}. The decision lives in {@link LobbyCountdownService}, which is gated by
 * {@link PermissionNodes#SPAWN_LOBBY} both when the countdown starts and again on its last second, so a player
 * whose node is removed mid-countdown is not moved. The source must be a player; console and command blocks
 * cannot start a countdown.
 *
 * <p>The Overworld is resolved by the countdown's adapter each time a request starts and is wrapped in
 * {@link LevelLobbyTarget}, which both reads the view and performs the move, so a player in another dimension is
 * transferred to the Overworld spawn. A failed start sends only a message and leaves the player where they were.
 *
 * <p>The player is a type parameter, matching {@link LobbyCountdownService}, so the command shape and its
 * non-player path can be exercised without a running server. Production uses {@code ServerPlayer} and reads it
 * from the command source.
 *
 * <p>The command is not registered when the dispatcher already has {@code /lobby}, so a command from another
 * mod wins instead of being replaced.
 */
public final class LobbyCommand<P> {
    public static final String NAME = "lobby";

    private final LobbyCountdownService<P> countdown;
    private final Function<CommandSourceStack, P> playerOf;

    /**
     * @param countdown the countdown owner that decides and reports
     * @param playerOf reads the executing player from a command source, or {@code null} for console and blocks
     */
    public LobbyCommand(LobbyCountdownService<P> countdown, Function<CommandSourceStack, P> playerOf) {
        this.countdown = Objects.requireNonNull(countdown, "countdown");
        this.playerOf = Objects.requireNonNull(playerOf, "playerOf");
    }

    /** The command tree, so its shape can be inspected without a dispatcher or a running game. */
    public LiteralArgumentBuilder<CommandSourceStack> builder() {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(NAME).executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        P player = playerOf.apply(source);
        if (player == null) {
            source.sendFailure(Messages.playersOnly());
            return 0;
        }
        LobbyCountdownService.StartResult result = countdown.start(player);
        if (result.started()) {
            // The start message already reached the player, so the command adds no second line.
            return Command.SINGLE_SUCCESS;
        }
        Component failure = result.message();
        if (failure != null) {
            source.sendFailure(failure);
        }
        return 0;
    }
}
