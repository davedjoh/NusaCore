package com.dave.nusacore.core.claim;

import com.dave.nusacore.core.cache.ClaimCache;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * The single gate between durable claim storage and the in-memory {@link ClaimCache}.
 *
 * <p>Ordering is fixed and one-directional. A mutation is committed durably first, and only a successful commit
 * schedules the cache update and the success result on the server thread. A duplicate key, a missing claim, or a
 * failed write therefore leaves the cache exactly as it was, and a caller that observes success is looking at a
 * cache that already reflects the committed claim. There is no method that writes the cache before the commit.
 *
 * <p>Reads are cache-only, so the game thread never performs I/O. A read before the initial load finishes sees an
 * empty view, which {@link #ready()} lets a caller detect.
 *
 * <p>This class carries no claim rules of its own: it does not decide permissions, cost, expiry, or which commands
 * exist. Claim management commands and protection are later slices; when they arrive they call these methods and the
 * ordering stays here. Results are values, never exceptions.
 */
public final class ClaimStore {
    private final ClaimRepository repository;
    private final ClaimCache cache;
    private final Executor serverThread;

    /**
     * @param repository the durable store
     * @param cache the committed in-memory view
     * @param serverThread where cache activation and success are delivered, normally
     *        {@code MinecraftServer::execute}; it must run tasks on the game thread
     */
    public ClaimStore(ClaimRepository repository, ClaimCache cache, Executor serverThread) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
    }

    /** The cache reads go through. Exposed so hot paths can hold it directly. */
    public ClaimCache cache() {
        return cache;
    }

    /** True when the initial load finished, so a cache miss really means unclaimed. */
    public boolean ready() {
        return cache.ready();
    }

    /**
     * Loads every stored claim into the cache.
     *
     * <p>Runs asynchronously so server start does not wait on disk. A row that cannot be read is reported in the
     * returned snapshot instead of failing the load, so one bad row cannot take the server down.
     */
    public CompletableFuture<StorageResult<ClaimRepository.ClaimSnapshot>> load() {
        return repository.loadAll().thenApply(result -> {
            if (result.ok()) {
                cache.loadAll(result.valueOrNull().claims());
            }
            return result;
        });
    }

    /**
     * A claim, from the cache only.
     *
     * <p>Empty when the chunk is unclaimed, or when the initial load has not finished yet; callers that must tell
     * those apart use {@link #ready()}.
     */
    public Optional<Claim> findCached(ClaimKey key) {
        return cache.get(key);
    }

    /**
     * Creates a claim, committing before activating it.
     *
     * <p>On success the cache already holds the claim when the returned future completes. On a duplicate key the
     * cache is untouched, so a rejected request never replaces a claim that already existed.
     */
    public CompletableFuture<StorageResult<Void>> create(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return commitThenActivate(() -> repository.create(claim), () -> cache.activateAfterCommit(claim));
    }

    /**
     * Replaces a claim, committing before activating it.
     *
     * <p>On success the cache holds {@code claim} when the returned future completes. On a missing claim nothing
     * changes, so a stale update never resurrects a deleted claim locally.
     */
    public CompletableFuture<StorageResult<Void>> update(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return commitThenActivate(() -> repository.update(claim), () -> cache.activateAfterCommit(claim));
    }

    /**
     * Deletes a claim, committing before removing it from the cache.
     *
     * <p>On success the claim is gone from the cache when the returned future completes. A failed delete leaves it
     * visible, so the cache never reports a chunk as unclaimed while the row still exists.
     */
    public CompletableFuture<StorageResult<Void>> delete(ClaimKey key) {
        Objects.requireNonNull(key, "key");
        return commitThenActivate(() -> repository.delete(key), () -> cache.removeAfterCommit(key));
    }

    /** The currently cached claims, for diagnostics and tests. */
    public List<Claim> cachedClaims() {
        return cache.snapshot();
    }

    /**
     * Drains pending writes and releases storage.
     *
     * <p>Blocking, so it belongs on the server stop path only.
     */
    public void close() {
        repository.close();
    }

    /**
     * Runs one durable commit, then the matching cache change and the success result on the server thread.
     *
     * <p>The commit future is observed on the storage worker. Only {@link StorageResult#ok()} reaches the activation,
     * so a failure is returned with the cache untouched.
     */
    private CompletableFuture<StorageResult<Void>> commitThenActivate(
            java.util.function.Supplier<CompletableFuture<StorageResult<Void>>> commit, Runnable activation) {
        CompletableFuture<StorageResult<Void>> result = new CompletableFuture<>();
        CompletableFuture<StorageResult<Void>> commitFuture;
        try {
            commitFuture = commit.get();
        } catch (RuntimeException callerFailure) {
            result.complete(StorageResult.failure(StorageFailure.FAILURE, "could not start the commit: " + callerFailure));
            return result;
        }
        commitFuture.whenComplete((outcome, error) -> {
            if (error != null) {
                result.complete(StorageResult.failure(
                        StorageFailure.FAILURE, "the commit did not complete: " + error));
                return;
            }
            if (outcome.failed()) {
                result.complete(StorageResult.failure(outcome.failureOrNull(), outcome.detail()));
                return;
            }
            activateOnServerThread(activation, result);
        });
        return result;
    }

    /**
     * Applies the cache change and completes the caller's future on the server thread.
     *
     * <p>If the server is already stopping, the server-thread executor rejects the task. The commit has succeeded by
     * then, so the activation is applied on the completing thread instead: a cache that disagrees with durable state
     * for the rest of the shutdown is worse than one update outside the game thread, and the cache is safe for it.
     */
    private void activateOnServerThread(Runnable activation, CompletableFuture<StorageResult<Void>> result) {
        Runnable task = () -> {
            try {
                activation.run();
                result.complete(StorageResult.done());
            } catch (RuntimeException activationFailure) {
                result.complete(StorageResult.failure(
                        StorageFailure.FAILURE, "the commit succeeded but the cache update failed: " + activationFailure));
            }
        };
        try {
            serverThread.execute(task);
        } catch (RejectedExecutionException stopping) {
            task.run();
        }
    }
}
