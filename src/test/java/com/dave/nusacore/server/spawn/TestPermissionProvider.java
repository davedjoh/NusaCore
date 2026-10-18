package com.dave.nusacore.server.spawn;

import java.util.concurrent.atomic.AtomicReference;

import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.util.TriState;

/**
 * A Fabric Permissions API provider that answers every check with one value, so a test can drive the production
 * permission gate the way a real provider such as LuckPerms does.
 *
 * <p>The provider is registered once for the test JVM and answers {@link TriState#DEFAULT} unless a test sets a
 * value, which is indistinguishable from having no provider: the NusaCore fallback denies a default answer. That
 * keeps every other test on the documented no-provider behaviour. Tests must {@link #clear()} in a finally block.
 */
final class TestPermissionProvider {
    private static final AtomicReference<TriState> ANSWER = new AtomicReference<>(TriState.DEFAULT);

    static {
        PermissionCheckEvent.EVENT.register((source, permission) -> ANSWER.get());
    }

    private TestPermissionProvider() {
    }

    /** Makes the provider grant or deny every node. */
    static void answer(TriState value) {
        ANSWER.set(value);
    }

    /** Restores the no-provider behaviour. */
    static void clear() {
        ANSWER.set(TriState.DEFAULT);
    }
}
