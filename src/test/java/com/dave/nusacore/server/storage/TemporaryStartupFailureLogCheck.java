package com.dave.nusacore.server.storage;

import com.dave.nusacore.core.storage.StorageResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryStartupFailureLogCheck {
    @TempDir
    Path directory;

    @Test
    void brokenDatabaseDirectoryIsReportedWithItsCause() throws Exception {
        Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "a file where the directory should be");
        ClaimStorageLifecycle.opener = ClaimStorageOpener.sqlite();

        ClaimStorageSession opened = ClaimStorageLifecycle.openWith(ClaimStorageLifecycle.opener,
                blocker.resolve("nusacore-claims.db"), "check-worker", Runnable::run);

        try {
            assertTrue(opened.openFuture().get(15, TimeUnit.SECONDS).failed(),
                    "a path under a regular file must report the opening failure");
            StorageResult<?> loaded = opened.load().get(15, TimeUnit.SECONDS);
            assertTrue(loaded.failed(), "a load against a database that never opened must fail");
            assertFalse(opened.ready(), "a failed open must never look ready");
        } finally {
            opened.close();
        }
    }
}
