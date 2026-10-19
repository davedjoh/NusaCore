package com.dave.nusacore.core.claim;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TrustedPermissionTest {
    @Test
    void tokensAreStableAndLowercase() {
        assertEquals("build", TrustedPermission.BUILD.token());
        assertEquals("containers", TrustedPermission.CONTAINERS.token());
        assertEquals("use", TrustedPermission.USE.token());
    }

    @Test
    void parsingRejectsUnknownAndRepeatedTokens() {
        assertEquals(Set.of(), TrustedPermission.parse(""));
        assertEquals(Set.of(), TrustedPermission.parse("   "));
        assertEquals(Set.of(TrustedPermission.BUILD, TrustedPermission.USE), TrustedPermission.parse("use,build"));

        assertThrows(IllegalArgumentException.class, () -> TrustedPermission.parse("build,fly"));
        assertThrows(IllegalArgumentException.class, () -> TrustedPermission.parse("build,build"));
        assertThrows(IllegalArgumentException.class, () -> TrustedPermission.parse("BUILD"));
        assertThrows(NullPointerException.class, () -> TrustedPermission.parse(null));
    }

    @Test
    void thereIsNoManagementPermission() {
        for (TrustedPermission permission : TrustedPermission.values()) {
            assertNotEquals("manage", permission.token());
            assertFalse(permission.token().contains("manage"),
                    "trusted players must never gain management rights");
            assertFalse(permission.token().contains("owner"));
            assertFalse(permission.token().contains("transfer"));
        }
    }
}
