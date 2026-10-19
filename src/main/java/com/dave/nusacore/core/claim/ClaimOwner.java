package com.dave.nusacore.core.claim;

import java.util.Objects;
import java.util.UUID;

/**
 * The owner of a claim.
 *
 * <p>A claim is either player-owned or an administrator claim. The two are distinct kinds rather than a nullable
 * UUID, so an administrator claim can never be mistaken for a player claim with a missing owner, and so the
 * database can enforce the same shape with a constraint.
 *
 * <p>Only the player owner manages a player claim. Trusted players gain access permissions but never management.
 * An administrator claim is managed by administrators only.
 */
public sealed interface ClaimOwner permits ClaimOwner.Player, ClaimOwner.Admin {
    /** A player owns the claim. Ownership transfer is future work. */
    record Player(UUID uuid) implements ClaimOwner {
        public Player {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    /** Nobody owns the claim: it is an administrator claim and no player may manage it. */
    record Admin() implements ClaimOwner {
    }

    /** The single administrator owner, since it carries no data. */
    Admin ADMIN = new Admin();

    /** A player owner, for readability at call sites. */
    static ClaimOwner player(UUID uuid) {
        return new Player(uuid);
    }
}
