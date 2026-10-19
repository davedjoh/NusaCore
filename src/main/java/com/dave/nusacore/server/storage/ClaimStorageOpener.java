package com.dave.nusacore.server.storage;

import com.dave.nusacore.core.storage.sqlite.SqliteClaimRepository;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * Starts claim storage for one server session without waiting for the disk.
 *
 * <p>The lifecycle hook runs on the server lifecycle thread, so this is the seam that keeps that thread free: an
 * opener may queue filesystem, JDBC, schema, and load work, but it must return before any of it finishes. The SQLite
 * opener does exactly that by delegating to {@link SqliteClaimRepository#openAsync(Path, String)}. Tests replace the
 * opener with one whose initialisation and load they control, to prove the lifecycle callback returns first and that
 * readiness flips only after the load succeeds.
 *
 * <p>An opener reports a failure it cannot even start through {@link #open} returning {@code null}, or by throwing;
 * the lifecycle logs both and leaves claims unavailable for that session instead of failing the server start.
 */
@FunctionalInterface
interface ClaimStorageOpener {
    /**
     * Starts storage and returns its handle.
     *
     * @param database the database file
     * @param workerThreadName the storage worker thread name, so a thread dump names the database it serves
     * @param delivery where the load outcome is reported, normally {@code MinecraftServer::execute}
     * @return the opening session, or {@code null} when storage could not be started
     */
    ClaimStorageSession open(Path database, String workerThreadName, Executor delivery);

    /** The production opener: an asynchronous SQLite repository whose disk work runs on its own worker. */
    static ClaimStorageOpener sqlite() {
        return (database, workerThreadName, delivery) -> {
            SqliteClaimRepository repository = SqliteClaimRepository.openAsync(database, workerThreadName);
            return new ClaimStorageSession(database, repository, repository.openFuture(), delivery);
        };
    }
}
