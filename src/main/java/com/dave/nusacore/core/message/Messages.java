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
    public static final String LOBBY_TELEPORT_FAILED = "nusacore.command.lobby.teleport_failed";
    public static final String LOBBY_NOT_AVAILABLE = "nusacore.command.lobby.not_available";

    public static final String CLAIM_CREATED = "nusacore.claim.created";
    public static final String CLAIM_ALREADY_OWNED = "nusacore.claim.already_owned";
    public static final String CLAIM_CHUNK_TAKEN = "nusacore.claim.chunk_taken";
    public static final String CLAIM_NOT_CLAIMED = "nusacore.claim.not_claimed";
    public static final String CLAIM_NOT_OWNER = "nusacore.claim.not_owner";
    public static final String CLAIM_REMOVED = "nusacore.claim.removed";
    public static final String CLAIM_ADMIN_CREATED = "nusacore.claim.admin.created";
    public static final String CLAIM_ADMIN_REMOVED = "nusacore.claim.admin.removed";
    public static final String CLAIM_STORAGE_UNAVAILABLE = "nusacore.claim.storage.unavailable";
    public static final String CLAIM_STORAGE_NOT_READY = "nusacore.claim.storage.not_ready";
    public static final String CLAIM_STORAGE_ERROR = "nusacore.claim.storage.error";
    public static final String CLAIM_TRUST_ADDED = "nusacore.claim.trust.added";
    public static final String CLAIM_TRUST_ALREADY = "nusacore.claim.trust.already";
    public static final String CLAIM_TRUST_REMOVED = "nusacore.claim.trust.removed";
    public static final String CLAIM_TRUST_NOT_FOUND = "nusacore.claim.trust.not_found";
    public static final String CLAIM_TRUST_SELF = "nusacore.claim.trust.self";
    public static final String CLAIM_PLAYER_NOT_FOUND = "nusacore.claim.player.not_found";

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

    /** The last second passed but the server refused the move, so the player stayed where they were. */
    public static Component lobbyTeleportFailed() {
        return MessageText.component(LOBBY_TELEPORT_FAILED);
    }

    /** The player is no longer connected, so no countdown can start or continue. */
    public static Component lobbyNotAvailable() {
        return MessageText.component(LOBBY_NOT_AVAILABLE);
    }

    /** The current chunk is now the player's one free claim. */
    public static Component claimCreated() {
        return MessageText.component(CLAIM_CREATED);
    }

    /** The player already owns a free claim, so no second one was created. */
    public static Component claimAlreadyOwned() {
        return MessageText.component(CLAIM_ALREADY_OWNED);
    }

    /** Another claim already covers the current chunk. */
    public static Component claimChunkTaken() {
        return MessageText.component(CLAIM_CHUNK_TAKEN);
    }

    /** There is no claim at the current chunk. */
    public static Component claimNotClaimed() {
        return MessageText.component(CLAIM_NOT_CLAIMED);
    }

    /** The claim exists, but the player is not its owner. */
    public static Component claimNotOwner() {
        return MessageText.component(CLAIM_NOT_OWNER);
    }

    /** The player's own claim was removed. */
    public static Component claimRemoved() {
        return MessageText.component(CLAIM_REMOVED);
    }

    /** An administrator claim was created at the current chunk. */
    public static Component claimAdminCreated() {
        return MessageText.component(CLAIM_ADMIN_CREATED);
    }

    /** An administrator removed the claim at the current chunk. */
    public static Component claimAdminRemoved() {
        return MessageText.component(CLAIM_ADMIN_REMOVED);
    }

    /** Claim storage is not open for this server session. */
    public static Component claimStorageUnavailable() {
        return MessageText.component(CLAIM_STORAGE_UNAVAILABLE);
    }

    /** Claim storage is still loading, so the request was not decided. */
    public static Component claimStorageNotReady() {
        return MessageText.component(CLAIM_STORAGE_NOT_READY);
    }

    /** The durable write failed, so nothing was changed. */
    public static Component claimStorageError() {
        return MessageText.component(CLAIM_STORAGE_ERROR);
    }

    /** A player was added to the claim's trusted list. */
    public static Component claimTrustAdded(String player) {
        return MessageText.component(CLAIM_TRUST_ADDED, player);
    }

    /** The player already had every requested permission. */
    public static Component claimTrustAlready(String player) {
        return MessageText.component(CLAIM_TRUST_ALREADY, player);
    }

    /** A player was removed from the claim's trusted list. */
    public static Component claimTrustRemoved(String player) {
        return MessageText.component(CLAIM_TRUST_REMOVED, player);
    }

    /** The player was not trusted on this claim. */
    public static Component claimTrustNotFound(String player) {
        return MessageText.component(CLAIM_TRUST_NOT_FOUND, player);
    }

    /** A claim owner cannot trust themselves. */
    public static Component claimTrustSelf() {
        return MessageText.component(CLAIM_TRUST_SELF);
    }

    /** A player name or UUID could not be resolved. */
    public static Component claimPlayerNotFound(String input) {
        return MessageText.component(CLAIM_PLAYER_NOT_FOUND, input);
    }
}
