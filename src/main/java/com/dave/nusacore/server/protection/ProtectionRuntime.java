package com.dave.nusacore.server.protection;

import com.dave.nusacore.core.claim.ClaimProtection;

/**
 * The protection instance for the current server session.
 *
 * <p>Fabric callbacks and mixins reach protection through this holder. It is installed when claim storage starts
 * and replaced with a deny-all instance when storage closes or fails to open, so a missing view is never treated as
 * an empty world.
 *
 * <p>It is {@code null} before any server starts and on a client, where there is nothing to protect. A dedicated
 * server installs an instance on every start, including a failed one.
 */
public final class ProtectionRuntime {
    private static volatile ClaimProtection current;

    private ProtectionRuntime() {
    }

    /** Publishes the protection instance for this server session. */
    public static void install(ClaimProtection protection) {
        current = protection;
    }

    /** Replaces the instance with deny-all, for a stop or a storage failure. */
    public static void denyAll() {
        current = ClaimProtection.unavailable();
    }

    /** The instance for this session, or {@code null} before a server starts and on a client. */
    public static ClaimProtection get() {
        return current;
    }
}
