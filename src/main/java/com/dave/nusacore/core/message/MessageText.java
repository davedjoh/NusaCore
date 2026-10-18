package com.dave.nusacore.core.message;

import java.util.Map;

import net.minecraft.network.chat.Component;

/**
 * Key to text mapping behind {@link Messages}. It lives separately so the mapping can be read and tested
 * without building components, and so a future language file only has to replace this lookup.
 */
public final class MessageText {
    private static final Map<String, String> ENGLISH = Map.of(
            Messages.NO_PERMISSION, "You do not have permission to use this command.",
            Messages.PLAYERS_ONLY, "Only players can use this command.",
            Messages.LOBBY_NO_OVERWORLD, "Could not reach the Overworld. You were not teleported.",
            Messages.LOBBY_NO_SAFE_DESTINATION, "No safe place near the world spawn was found. You were not teleported.",
            Messages.LOBBY_SUCCESS, "Teleported to the world spawn.");

    private MessageText() {
    }

    /** Resolves the text for a key, or the key itself when it is missing. */
    public static String resolve(String key) {
        return ENGLISH.getOrDefault(key, key);
    }

    /** Component for a key. All player-facing output goes through this method. */
    public static Component component(String key) {
        return Component.literal(resolve(key));
    }

    /** Every key that has text, for coverage tests. */
    public static java.util.Set<String> keys() {
        return ENGLISH.keySet();
    }
}
