package com.dave.nusacore.core.message;

import net.minecraft.network.chat.Component;

/**
 * Central player-facing text. Command and gameplay code must not hardcode text; it resolves a key here.
 *
 * <p>Messages are literal English components for now. The keys are stable, so a future language file can
 * replace {@link MessageText#resolve(String)} without touching command code.
 */
public final class Messages {
    public static final String NO_PERMISSION = "command.nusacore.no_permission";
    public static final String PLAYERS_ONLY = "command.nusacore.players_only";
    public static final String LOBBY_NO_OVERWORLD = "nusacore.command.lobby.no_overworld";
    public static final String LOBBY_NO_SAFE_DESTINATION = "nusacore.command.lobby.no_safe_destination";
    public static final String LOBBY_SUCCESS = "nusacore.command.lobby.success";

    private Messages() {
    }

    /** Failure message used by the permission gate when the node is denied. */
    public static Component noPermission() {
        return MessageText.component(NO_PERMISSION);
    }

    /** Failure message for a source that is not a player, such as console or a command block. */
    public static Component playersOnly() {
        return MessageText.component(PLAYERS_ONLY);
    }

    /** The Overworld is missing or unusable, so nothing was teleported. */
    public static Component lobbyNoOverworld() {
        return MessageText.component(LOBBY_NO_OVERWORLD);
    }

    /** No safe standing position was found close to the world spawn. */
    public static Component lobbyNoSafeDestination() {
        return MessageText.component(LOBBY_NO_SAFE_DESTINATION);
    }

    /** The player now stands at the Overworld world spawn. */
    public static Component lobbySuccess() {
        return MessageText.component(LOBBY_SUCCESS);
    }
}
