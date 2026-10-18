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
            Map.entry(Messages.LOBBY_NOT_AVAILABLE, "You are no longer connected, so nothing was teleported."));

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
