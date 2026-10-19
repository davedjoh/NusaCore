package com.dave.nusacore.server.storage;

import com.dave.nusacore.core.cache.ClaimCache;
import com.dave.nusacore.core.claim.ClaimStore;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageResult;
import com.dave.nusacore.core.storage.sqlite.SqliteClaimRepository;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * One opened storage session: the repository an opener produced, the committed cache over it, and the readiness gate.
 *
 * <p>Owned by {@link ClaimStorageLifecycle}. This class holds no server types, so the lifecycle can be driven in a
 * test with a database file in a temporary directory and a plain executor.
 */
final class ClaimStorageSession {
    private final Path database;
    private final ClaimRepository repository;
    private final CompletableFuture<StorageResult<Path>> openFuture;
    private final Executor delivery;
    private final ClaimCache cache = new ClaimCache();
    private final ClaimStore store;
    private final CompletableFuture<StorageResult<ClaimRepository.ClaimSnapshot>> load;

    /** True once the initial load reported an outcome, successful or not. */
    private volatile boolean loadFinished;
    private volatile StorageResult<ClaimRepository.ClaimSnapshot> result;
    private volatile Throwable failure;

    ClaimStorageSession(Path database, ClaimRepository repository, CompletableFuture<StorageResult<Path>> openFuture,
            Executor delivery) {
        this.database = Objects.requireNonNull(database, "database");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.openFuture = Objects.requireNonNull(openFuture, "openFuture");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        // Cache activation and command replies go to the caller's executor, normally the server thread, never to the
        // storage worker.
        this.store = new ClaimStore(repository, cache, delivery);
        // Starting the load only queues it on the storage worker; this constructor does not wait for it.
        this.load = store.load().whenComplete((loaded, loadFailure) -> {
            this.result = loaded;
            this.failure = loadFailure;
            this.loadFinished = true;
        });
    }

    /** Where this session's database lives. */
    Path database() {
        return database;
    }

    /** The committed claim view. Reads are cache-only. */
    ClaimStore store() {
        return store;
    }

    /** The committed claims, for a read path that holds the cache directly. */
    ClaimCache cache() {
        return cache;
    }

    /** The initialisation outcome, so a failed load can report why storage never opened. */
    CompletableFuture<StorageResult<Path>> openFuture() {
        return openFuture;
    }

    /** The initial load, already queued on the storage worker by the constructor. */
    CompletableFuture<StorageResult<ClaimRepository.ClaimSnapshot>> load() {
        return load;
    }

    /**
     * True when the database opened, the schema pass and the initial load all succeeded, so a cache miss really means
     * unclaimed.
     *
     * <p>The open outcome, the repository, the in-memory view, and this session's own record of the load must all
     * agree, so a failure in one half is never hidden by another. The open future is checked directly rather than
     * only through the repository, so a repository that reports a successful load while its initialisation is still
     * pending (or failed) cannot make the session look ready.
     */
    boolean ready() {
        return loadFinished && failure == null && result != null && result.ok() && opened()
                && repositoryReady() && store.ready();
    }

    private boolean opened() {
        StorageResult<Path> opening = openResult();
        return opening != null && opening.ok();
    }

    /**
     * The opening outcome if it arrived normally, or {@code null} while pending or after an exceptional completion.
     *
     * <p>Production completes this future with a {@link StorageResult} failure rather than exceptionally, but an
     * opener seam is free to complete it exceptionally; readiness must stay false in that case instead of throwing
     * from a gameplay read.
     */
    StorageResult<Path> openResult() {
        try {
            return openFuture.getNow(null);
        } catch (CompletionException | CancellationException unfinished) {
            return null;
        }
    }

    private boolean repositoryReady() {
        return !(repository instanceof SqliteClaimRepository sqlite) || sqlite.ready();
    }

    /** The load result once it arrived, or {@code null} while loading or after an exceptional failure. */
    StorageResult<ClaimRepository.ClaimSnapshot> result() {
        return result;
    }

    /** The exception that ended the load, or {@code null} when the load reported a result instead. */
    Throwable failure() {
        return failure;
    }

    /** Drains accepted writes and releases storage. Blocking: server stop path only. */
    void close() {
        store.close();
    }
}
