package com.dave.nusacore.core.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test
    void everyDeclaredKeyHasText() {
        for (String key : MessageText.keys()) {
            assertNotEquals(key, MessageText.resolve(key), "missing text for " + key);
        }
        assertTrue(MessageText.keys().contains(Messages.LOBBY_SUCCESS));
        assertTrue(MessageText.keys().contains(Messages.NO_PERMISSION));
    }

    @Test
    void lobbyTextIsPlayerFacingAndAsciiOnly() {
        String success = MessageText.resolve(Messages.LOBBY_SUCCESS);
        String noOverworld = MessageText.resolve(Messages.LOBBY_NO_OVERWORLD);
        String noSafe = MessageText.resolve(Messages.LOBBY_NO_SAFE_DESTINATION);
        String denied = MessageText.resolve(Messages.NO_PERMISSION);

        for (String text : new String[] {success, noOverworld, noSafe, denied}) {
            assertFalse(text.contains("\u2014"), "messages must not use em dashes");
            assertTrue(text.chars().allMatch(c -> c < 128), "messages stay plain ASCII so every client renders them");
            assertFalse(text.isBlank());
        }
        assertTrue(noOverworld.contains("not teleported"));
        assertTrue(noSafe.contains("not teleported"));
    }

    @Test
    void componentsCarryTheSameTextAsTheMapping() {
        assertEquals(MessageText.resolve(Messages.LOBBY_SUCCESS), Messages.lobbySuccess().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_NO_OVERWORLD), Messages.lobbyNoOverworld().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_NO_SAFE_DESTINATION), Messages.lobbyNoSafeDestination().getString());
        assertEquals(MessageText.resolve(Messages.NO_PERMISSION), Messages.noPermission().getString());
        assertEquals(MessageText.resolve(Messages.PLAYERS_ONLY), Messages.playersOnly().getString());
    }
}
