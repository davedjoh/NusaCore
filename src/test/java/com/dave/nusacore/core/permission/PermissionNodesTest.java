package com.dave.nusacore.core.permission;

import com.dave.nusacore.core.message.MessageText;
import com.dave.nusacore.core.message.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionNodesTest {
    @Test
    void lobbyUsesTheDocumentedNode() {
        assertEquals("nusacore.spawn.lobby", PermissionNodes.SPAWN_LOBBY);
        assertTrue(PermissionNodes.SPAWN_LOBBY.startsWith("nusacore.spawn."));
    }

    @Test
    void nothingIsGrantedWithoutAProvider() {
        assertFalse(PermissionNodes.DEFAULT_DENIED);
        // No permission provider is installed in the test runtime, so the fallback decides the result.
        assertFalse(PermissionNodes.check(source(), PermissionNodes.SPAWN_LOBBY));
    }

    @Test
    void denialTextComesFromTheMessageBoundary() {
        assertEquals(MessageText.resolve(Messages.NO_PERMISSION), PermissionNodes.denied().getString());
        assertFalse(PermissionNodes.denied().getString().isBlank());
    }

    /** A source without an entity or server; enough for a permission lookup with no provider installed. */
    private static CommandSourceStack source() {
        return new CommandSourceStack(
                null,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                null,
                "test",
                Component.literal("test"),
                null,
                null);
    }
}
