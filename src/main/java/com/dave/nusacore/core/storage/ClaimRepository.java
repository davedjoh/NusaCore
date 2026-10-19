package com.dave.nusacore.core.storage;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimKey;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Durable claim storage.
 *
 * <p>Every call returns a future that completes off the game thread, and every outcome is a {@link StorageResult}:
 * a duplicate key, a missing claim, and an unreadable row are values, not exceptions. The future itself does not
 * complete exceptionally for those cases, so a caller can only proceed by looking at the result.
 *
 * <p>The interface is the only claim-storage type the rest of the mod sees. SQLite, JDBC, and SQL stay behind
 * {@link com.dave.nusacore.core.storage.sqlite.SqliteClaimRepository}, so a different backend can replace it without
 * touching the claim module.
 *
 * <p>Writes are durable when the future completes successfully. A caller must not update its in-memory view before
 * that point; {@link com.dave.nusacore.core.claim.ClaimStore} is the one place that sequences commit and cache
 * activation.
 */
public interface ClaimRepository extends AutoCloseable {
    /** One claim, or {@link StorageFailure#NOT_FOUND}. */
    CompletableFuture<StorageResult<Claim>> find(ClaimKey key);

    /**
     * Every stored claim.
     *
     * <p>A row that cannot be read does not fail the whole load: it is reported in
     * {@link ClaimSnapshot#unreadableRows()} so startup can log it and keep serving the readable claims.
     */
    CompletableFuture<StorageResult<ClaimSnapshot>> loadAll();

    /** Stores a new claim, or fails with {@link StorageFailure#DUPLICATE_KEY} when the key is taken. */
    CompletableFuture<StorageResult<Void>> create(Claim claim);

    /** Replaces an existing claim, or fails with {@link StorageFailure#NOT_FOUND} when it is gone. */
    CompletableFuture<StorageResult<Void>> update(Claim claim);

    /** Removes a claim and its trusted players, or fails with {@link StorageFailure#NOT_FOUND}. */
    CompletableFuture<StorageResult<Void>> delete(ClaimKey key);

    /**
     * Drains pending writes and releases the storage resources.
     *
     * <p>This blocks, so it is only for the server shutdown path. Calling it from a tick, command, or event handler
     * would stall the server.
     */
    @Override
    void close();

    /**
     * The claims that loaded, plus the rows that could not be read.
     *
     * @param claims readable claims, in dimension and chunk order
     * @param unreadableRows one description per skipped row, for the startup log
     */
    record ClaimSnapshot(List<Claim> claims, List<String> unreadableRows) {
        public ClaimSnapshot {
            claims = List.copyOf(claims);
            unreadableRows = List.copyOf(unreadableRows);
        }

        /** True when every stored row loaded. */
        public boolean complete() {
            return unreadableRows.isEmpty();
        }
    }
}
