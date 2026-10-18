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
    public static final String LOBBY_COUNTDOWN_STARTED = "nusacore.command.lobby.countdown.started";
    public static final String LOBBY_COUNTDOWN_TICK = "nusacore.command.lobby.countdown.tick";
    public static final String LOBBY_ALREADY_PENDING = "nusacore.command.lobby.countdown.already_pending";
    public static final String LOBBY_COUNTDOWN_CANCELLED_MOVED = "nusacore.command.lobby.countdown.cancelled.moved";
    public static final String LOBBY_COUNTDOWN_CANCELLED_DIED = "nusacore.command.lobby.countdown.cancelled.died";
    public static final String LOBBY_COUNTDOWN_CANCELLED_DIMENSION =
            "nusacore.command.lobby.countdown.cancelled.dimension";
    public static final String LOBBY_COUNTDOWN_CANCELLED_DENIED =
            "nusacore.command.lobby.countdown.cancelled.denied";
    public static final String LOBBY_COUNTDOWN_CANCELLED_DISCONNECTED =
            "nusacore.command.lobby.countdown.cancelled.disconnected";
    public static final String LOBBY_COUNTDOWN_SERVER_STOPPING = "nusacore.command.lobby.countdown.server_stopping";

    /** Number of seconds in one lobby countdown, the unit the countdown messages are formatted with. */
    public static final int LOBBY_COUNTDOWN_SECONDS = 5;

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

    /** The countdown has started and the {@code seconds}-digit number arrives one second at a time. */
    public static Component lobbyCountdownStarted(int seconds) {
        return MessageText.component(LOBBY_COUNTDOWN_STARTED, seconds);
    }

    /** One second of the countdown, {@code seconds} remaining. */
    public static Component lobbyCountdownTick(int seconds) {
        return MessageText.component(LOBBY_COUNTDOWN_TICK, seconds);
    }

    /** A lobby request is already running, so this one does nothing. */
    public static Component lobbyAlreadyPending() {
        return MessageText.component(LOBBY_ALREADY_PENDING);
    }

    /** The player moved during the countdown, so the teleport was cancelled. */
    public static Component lobbyCountdownCancelledMoved() {
        return MessageText.component(LOBBY_COUNTDOWN_CANCELLED_MOVED);
    }

    /** The player died during the countdown, so the teleport was cancelled. */
    public static Component lobbyCountdownCancelledDied() {
        return MessageText.component(LOBBY_COUNTDOWN_CANCELLED_DIED);
    }

    /** The player changed dimension during the countdown, so the teleport was cancelled. */
    public static Component lobbyCountdownCancelledDimension() {
        return MessageText.component(LOBBY_COUNTDOWN_CANCELLED_DIMENSION);
    }

    /** The permission node was gone when the countdown finished, so the teleport was cancelled. */
    public static Component lobbyCountdownCancelledDenied() {
        return MessageText.component(LOBBY_COUNTDOWN_CANCELLED_DENIED);
    }

    /** The player disconnected, which ended the countdown. Never delivered. */
    public static Component lobbyCountdownCancelledDisconnected() {
        return MessageText.component(LOBBY_COUNTDOWN_CANCELLED_DISCONNECTED);
    }

    /** The server is stopping, which ended the countdown. Sent while the player can still receive it. */
    public static Component lobbyCountdownServerStopping() {
        return MessageText.component(LOBBY_COUNTDOWN_SERVER_STOPPING);
    }
}
