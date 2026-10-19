package com.dave.nusacore.core.message;

import java.util.Map;

import net.minecraft.network.chat.Component;

/**
 * Key to text mapping behind {@link Messages}. It lives separately so the mapping can be read and tested
 * without building components, and so a future language file only has to replace this lookup.
 */
public final class MessageText {
    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry(Messages.NO_PERMISSION, "You do not have permission to use this command."),
            Map.entry(Messages.PLAYERS_ONLY, "Only players can use this command."),
            Map.entry(Messages.LOBBY_NO_OVERWORLD, "Could not reach the Overworld. You were not teleported."),
            Map.entry(Messages.LOBBY_NO_SAFE_DESTINATION,
                    "No safe place near the world spawn was found. You were not teleported."),
            Map.entry(Messages.LOBBY_SUCCESS, "Teleported to the world spawn."),
            Map.entry(Messages.LOBBY_COUNTDOWN_STARTED,
                    "Teleporting to the world spawn in %s seconds. Do not move."),
            Map.entry(Messages.LOBBY_COUNTDOWN_TICK, "Teleporting in %s..."),
            Map.entry(Messages.LOBBY_ALREADY_PENDING, "A lobby teleport is already counting down."),
            Map.entry(Messages.LOBBY_COUNTDOWN_CANCELLED_MOVED, "Lobby teleport cancelled because you moved."),
            Map.entry(Messages.LOBBY_COUNTDOWN_CANCELLED_DIED, "Lobby teleport cancelled because you died."),
            Map.entry(Messages.LOBBY_COUNTDOWN_CANCELLED_DIMENSION,
                    "Lobby teleport cancelled because you changed dimension."),
            Map.entry(Messages.LOBBY_COUNTDOWN_CANCELLED_DENIED,
                    "Lobby teleport cancelled because you no longer have permission."),
            Map.entry(Messages.LOBBY_COUNTDOWN_CANCELLED_DISCONNECTED,
                    "Lobby teleport cancelled because you disconnected."),
            Map.entry(Messages.LOBBY_COUNTDOWN_SERVER_STOPPING,
                    "Lobby teleport cancelled because the server is stopping."),
            Map.entry(Messages.LOBBY_TELEPORT_FAILED, "The server could not move you. You were not teleported."),
            Map.entry(Messages.LOBBY_NOT_AVAILABLE, "You are no longer connected, so nothing was teleported."),
            Map.entry(Messages.CLAIM_CREATED, "Claimed this chunk. This is your one free claim."),
            Map.entry(Messages.CLAIM_ALREADY_OWNED,
                    "You already own a claim. Each player gets one free chunk."),
            Map.entry(Messages.CLAIM_CHUNK_TAKEN, "This chunk is already claimed."),
            Map.entry(Messages.CLAIM_NOT_CLAIMED, "This chunk is not claimed."),
            Map.entry(Messages.CLAIM_NOT_OWNER, "Only the claim owner can do that."),
            Map.entry(Messages.CLAIM_REMOVED, "Unclaimed this chunk."),
            Map.entry(Messages.CLAIM_ADMIN_CREATED, "Created an administrator claim on this chunk."),
            Map.entry(Messages.CLAIM_ADMIN_REMOVED, "Removed the claim on this chunk."),
            Map.entry(Messages.CLAIM_STORAGE_UNAVAILABLE, "Claim storage is not available. Nothing was changed."),
            Map.entry(Messages.CLAIM_STORAGE_NOT_READY, "Claims are still loading. Try again shortly."),
            Map.entry(Messages.CLAIM_STORAGE_ERROR, "The claim change could not be saved. Nothing was changed."),
            Map.entry(Messages.CLAIM_TRUST_ADDED, "Trusted %s on this claim."),
            Map.entry(Messages.CLAIM_TRUST_ALREADY, "%s already has those permissions on this claim."),
            Map.entry(Messages.CLAIM_TRUST_REMOVED, "Removed %s from this claim's trusted list."),
            Map.entry(Messages.CLAIM_TRUST_NOT_FOUND, "%s is not trusted on this claim."),
            Map.entry(Messages.CLAIM_TRUST_SELF, "You cannot trust yourself."),
            Map.entry(Messages.CLAIM_PLAYER_NOT_FOUND, "Could not find a player named %s."));

    private MessageText() {
    }

    /** Resolves the text for a key, or the key itself when it is missing. */
    public static String resolve(String key) {
        return ENGLISH.getOrDefault(key, key);
    }

    /** Resolves the text for a key and fills its placeholders. */
    public static String resolve(String key, Object... arguments) {
        return String.format(resolve(key), arguments);
    }

    /** Component for a key. All player-facing output goes through this method. */
    public static Component component(String key) {
        return Component.literal(resolve(key));
    }

    /** Component for a key with filled placeholders. */
    public static Component component(String key, Object... arguments) {
        return Component.literal(resolve(key, arguments));
    }

    /** Every key that has text, for coverage tests. */
    public static java.util.Set<String> keys() {
        return ENGLISH.keySet();
    }
}
