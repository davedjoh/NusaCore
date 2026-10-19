package com.dave.nusacore.core.permission;

import java.util.Objects;

import com.dave.nusacore.core.message.MessageText;
import com.dave.nusacore.core.message.Messages;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;

/**
 * The single registry of NusaCore permission nodes.
 *
 * <p>Nodes use the {@code nusacore.<module>.<action>} layout so LuckPerms can grant them individually.
 *
 * <p>Fallback: the Fabric Permissions API invokes its provider event and returns {@code TriState.DEFAULT} when
 * no provider answers. Every NusaCore node treats that as denied, so with LuckPerms absent the commands stay
 * locked instead of open. No node is granted by default and there is no operator bypass; the node has to be
 * granted to a LuckPerms group or user. LuckPerms operators are not exempt either, because the gate checks the
 * node rather than the vanilla permission level.
 */
public final class PermissionNodes {
    /** Fallback used when no permission provider (for example LuckPerms) supplies a value. */
    public static final boolean DEFAULT_DENIED = false;

    /** Allows {@code /lobby}, the teleport to the Overworld world spawn. */
    public static final String SPAWN_LOBBY = "nusacore.spawn.lobby";

    /** Allows {@code /claim}, claiming the player's current chunk. */
    public static final String CLAIM_CREATE = "nusacore.claim.create";

    /** Allows {@code /unclaim}, releasing the player's own claim. */
    public static final String CLAIM_UNCLAIM = "nusacore.claim.unclaim";

    /** Allows {@code /trust} and {@code /untrust} on the player's own claim. */
    public static final String CLAIM_TRUST = "nusacore.claim.trust";

    /** Allows creating and removing administrator claims through {@code /claim admin}. */
    public static final String CLAIM_ADMIN = "nusacore.claim.admin";

    /**
     * Skips claim protection. This is the one bypass node named in the project specification.
     *
     * <p>It is deliberately separate from {@link #CLAIM_ADMIN}: bypassing protection never grants the right to
     * create, remove, or manage a claim.
     */
    public static final String CLAIM_BYPASS = "nusacore.admin.bypass";

    private PermissionNodes() {
    }

    /** Checks a node for a command source, denying when no provider answers. */
    public static boolean check(CommandSourceStack source, String node) {
        return Permissions.check(source, Objects.requireNonNull(node, "node"), DEFAULT_DENIED);
    }

    /** The message shown when a command is denied because the node is missing. */
    public static net.minecraft.network.chat.Component denied() {
        return MessageText.component(Messages.NO_PERMISSION);
    }
}
