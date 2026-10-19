package com.dave.nusacore.core.claim;

import com.dave.nusacore.core.cache.ClaimCache;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ordering guarantee: a claim reaches the cache only after the durable write reported success, and a failed
 * write leaves the cache exactly as it was.
 */
class ClaimStoreTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final ClaimKey KEY = new ClaimKey("minecraft:overworld", 0, 0);

    private final RecordingRepository repository = new RecordingRepository();
    private final ClaimCache cache = new ClaimCache();
    private final List<String> serverThreadLog = new ArrayList<>();
    private final AtomicInteger serverThreadTasks = new AtomicInteger();
    private final ClaimStore store = new ClaimStore(repository, cache, recordingServerThread());

    @Test
    void readsComeFromTheCacheOnlyAndAreEmptyUntilTheLoadFinishes() {
        assertFalse(store.ready());
        assertTrue(store.findCached(KEY).isEmpty());

        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        repository.stored.put(KEY, claim);
        store.load().join();

        assertTrue(store.ready());
        assertEquals(claim, store.findCached(KEY).orElseThrow());
        assertEquals("loadAll", repository.calls.get(0), "a read must not go to storage");
    }

    @Test
    void aFailedLoadLeavesTheCacheUnready() {
        repository.loadResult = StorageResult.failure(StorageFailure.FAILURE, "disk gone");

        StorageResult<ClaimRepository.ClaimSnapshot> result = store.load().join();

        assertTrue(result.failed());
        assertFalse(store.ready(), "an unreadable store must not look like an empty world");
        assertTrue(store.findCached(KEY).isEmpty());
    }

    @Test
    void aCreateActivatesTheClaimOnlyAfterTheCommitSucceeded() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);

        StorageResult<Void> result = store.create(claim).join();

        assertTrue(result.ok());
        assertEquals(List.of("create"), repository.calls);
        assertEquals(claim, store.findCached(KEY).orElseThrow());
        assertCacheActivationFollowedTheCommit();
    }

    @Test
    void aDuplicateKeyLeavesTheCacheUntouched() {
        Claim existing = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        cache.loadAll(List.of(existing));
        repository.createResult = StorageResult.failure(StorageFailure.DUPLICATE_KEY, "taken");
        Claim attempted = Claim.created(KEY, ClaimOwner.player(UUID.randomUUID()), CREATED);

        StorageResult<Void> result = store.create(attempted).join();

        assertEquals(StorageFailure.DUPLICATE_KEY, result.failureOrNull());
        assertEquals(existing, store.findCached(KEY).orElseThrow(), "a rejected claim must not replace the existing one");
        assertEquals(0, serverThreadTasks.get(), "a failed commit must not activate anything");
    }

    @Test
    void aFailedCommitDetailIsPassedThrough() {
        repository.createResult = StorageResult.failure(StorageFailure.FAILURE, "database is locked");

        StorageResult<Void> result = store.create(Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)).join();

        assertEquals(StorageFailure.FAILURE, result.failureOrNull());
        assertEquals("database is locked", result.detail());
        assertTrue(store.findCached(KEY).isEmpty());
    }

    @Test
    void anUpdateActivatesTheReplacement() {
        Claim original = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        cache.loadAll(List.of(original));
        Claim updated = original.withExpiresAt(Instant.parse("2026-03-01T00:00:00Z"));

        assertTrue(store.update(updated).join().ok());

        assertEquals(updated, store.findCached(KEY).orElseThrow());
        assertCacheActivationFollowedTheCommit();
    }

    @Test
    void anUpdateOfAMissingClaimLeavesTheCacheUntouched() {
        repository.updateResult = StorageResult.failure(StorageFailure.NOT_FOUND, "gone");

        StorageResult<Void> result = store.update(Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)).join();

        assertEquals(StorageFailure.NOT_FOUND, result.failureOrNull());
        assertTrue(store.findCached(KEY).isEmpty(), "a stale update must not resurrect a deleted claim locally");
        assertEquals(0, serverThreadTasks.get());
    }

    @Test
    void aDeleteRemovesTheClaimOnlyAfterTheCommit() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        cache.loadAll(List.of(claim));

        assertTrue(store.delete(KEY).join().ok());

        assertTrue(store.findCached(KEY).isEmpty());
        assertCacheActivationFollowedTheCommit();
    }

    @Test
    void aFailedDeleteKeepsTheClaimVisible() {
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);
        cache.loadAll(List.of(claim));
        repository.deleteResult = StorageResult.failure(StorageFailure.FAILURE, "database is locked");

        StorageResult<Void> result = store.delete(KEY).join();

        assertEquals(StorageFailure.FAILURE, result.failureOrNull());
        assertEquals(claim, store.findCached(KEY).orElseThrow(), "a claim must not look deleted while its row exists");
        assertEquals(0, serverThreadTasks.get());
    }

    @Test
    void anExceptionalCommitBecomesAFailureAndTouchesNothing() {
        repository.createThrows = true;

        StorageResult<Void> result = store.create(Claim.created(KEY, ClaimOwner.player(OWNER), CREATED)).join();

        assertEquals(StorageFailure.FAILURE, result.failureOrNull());
        assertTrue(store.findCached(KEY).isEmpty());
    }

    @Test
    void aRejectedServerThreadStillAppliesTheCommittedClaim() {
        ClaimStore stoppingStore = new ClaimStore(repository, cache, task -> {
            throw new RejectedExecutionException("server stopping");
        });
        Claim claim = Claim.created(KEY, ClaimOwner.player(OWNER), CREATED);

        StorageResult<Void> result = stoppingStore.create(claim).join();

        assertTrue(result.ok(), "the commit already happened, so the caller must be told it succeeded");
        assertEquals(claim, stoppingStore.findCached(KEY).orElseThrow(), "the cache must not disagree with storage");
    }

    @Test
    void theCallerIsNotBlockedWhileTheCommitIsPending() {
        CompletableFuture<StorageResult<Void>> pending = new CompletableFuture<>();
        repository.createPending = pending;

        CompletableFuture<StorageResult<Void>> result =
                store.create(Claim.created(KEY, ClaimOwner.player(OWNER), CREATED));

        assertFalse(result.isDone(), "the store must not wait for the commit on the calling thread");
        assertTrue(serverThreadLog.isEmpty());

        pending.complete(StorageResult.done());

        assertTrue(result.join().ok());
        assertEquals(List.of("activation"), serverThreadLog);
    }

    @Test
    void aMissingArgumentIsACallerError() {
        assertThrows(NullPointerException.class, () -> new ClaimStore(null, cache, Runnable::run));
        assertThrows(NullPointerException.class, () -> new ClaimStore(repository, null, Runnable::run));
        assertThrows(NullPointerException.class, () -> new ClaimStore(repository, cache, null));
        assertThrows(NullPointerException.class, () -> store.create(null));
        assertThrows(NullPointerException.class, () -> store.update(null));
        assertThrows(NullPointerException.class, () -> store.delete(null));
    }

    private void assertCacheActivationFollowedTheCommit() {
        assertEquals(1, serverThreadTasks.get(), "the cache update and the result belong on the server thread");
        assertEquals(List.of("activation"), serverThreadLog);
    }

    /** Runs the task immediately but records it, so the test can see what was scheduled and in which order. */
    private Executor recordingServerThread() {
        return task -> {
            serverThreadTasks.incrementAndGet();
            serverThreadLog.add("activation");
            task.run();
        };
    }

    /** A repository that records the calls and answers with results the test chooses. */
    private static final class RecordingRepository implements ClaimRepository {
        private final Map<ClaimKey, Claim> stored = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private StorageResult<ClaimSnapshot> loadResult;
        private StorageResult<Void> createResult = StorageResult.done();
        private StorageResult<Void> updateResult = StorageResult.done();
        private StorageResult<Void> deleteResult = StorageResult.done();
        private CompletableFuture<StorageResult<Void>> createPending;
        private boolean createThrows;

        @Override
        public CompletableFuture<StorageResult<Claim>> find(ClaimKey key) {
            calls.add("find");
            return CompletableFuture.completedFuture(stored.containsKey(key)
                    ? StorageResult.success(stored.get(key))
                    : StorageResult.failure(StorageFailure.NOT_FOUND));
        }

        @Override
        public CompletableFuture<StorageResult<ClaimSnapshot>> loadAll() {
            calls.add("loadAll");
            if (loadResult != null) {
                return CompletableFuture.completedFuture(loadResult);
            }
            return CompletableFuture.completedFuture(
                    StorageResult.success(new ClaimSnapshot(List.copyOf(stored.values()), List.of())));
        }

        @Override
        public CompletableFuture<StorageResult<Void>> create(Claim claim) {
            calls.add("create");
            if (createThrows) {
                CompletableFuture<StorageResult<Void>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("boom"));
                return failed;
            }
            if (createPending != null) {
                return createPending;
            }
            if (createResult.ok()) {
                stored.put(claim.key(), claim);
            }
            return CompletableFuture.completedFuture(createResult);
        }

        @Override
        public CompletableFuture<StorageResult<Void>> update(Claim claim) {
            calls.add("update");
            if (updateResult.ok()) {
                stored.put(claim.key(), claim);
            }
            return CompletableFuture.completedFuture(updateResult);
        }

        @Override
        public CompletableFuture<StorageResult<Void>> delete(ClaimKey key) {
            calls.add("delete");
            if (deleteResult.ok()) {
                stored.remove(key);
            }
            return CompletableFuture.completedFuture(deleteResult);
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }
}
