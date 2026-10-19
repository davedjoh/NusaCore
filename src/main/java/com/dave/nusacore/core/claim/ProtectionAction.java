package com.dave.nusacore.core.claim;

/**
 * The kind of access a protection check needs.
 *
 * <p>Each action maps to exactly one trust scope, so a player trusted only to open containers cannot break blocks
 * and a player trusted only to build cannot open a chest.
 */
public enum ProtectionAction {
    /** Breaking, placing, attacking a block, or attacking an entity. */
    BUILD(TrustedPermission.BUILD),

    /** Opening a chest, barrel, furnace, hopper, shulker box, or a container minecart. */
    CONTAINERS(TrustedPermission.CONTAINERS),

    /** Using an item on a block, such as a bucket, bonemeal, or a spawn egg. */
    USE(TrustedPermission.USE);

    private final TrustedPermission permission;

    ProtectionAction(TrustedPermission permission) {
        this.permission = permission;
    }

    /** The trust scope that grants this action. */
    public TrustedPermission permission() {
        return permission;
    }
}
