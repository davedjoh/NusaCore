package com.dave.nusacore.server.storage;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.claim.ClaimOwner;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lifecycle contract: the open path returns before any disk work finishes, readiness flips only after the
 * asynchronous initialisation and load succeed, and a failure is reported instead of presenting an empty world.
 *
 * <p>These tests use the {@link ClaimStorageOpener} seam with a repository whose open future and load they hold open,
 * so the ordering is asserted, not assumed from timing. {@link ClaimStorageLifecycle#start(Path, Executor)} is the
 * same path {@code SERVER_STARTING} uses.
 */
class ClaimStorageLifecycleTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

    @TempDir
    Path directory;

    @AfterEach
    void restoreProductionOpener() {
        ClaimStorageLifecycle.opener = ClaimStorageOpener.sqlite();
        ClaimStorageLifecycle.close();
    }

    @Test
    void startReturnsBeforeABlockedInitialisationAndReadinessFollowsTheAsyncLoad() throws Exception {
        Path database = directory.resolve("blocked").resolve("nusacore-claims.db");
        GatedInitialisationRepository gated = new GatedInitialisationRepository();
        RecordingExecutor serverThread = new RecordingExecutor();

        ClaimStorageSession opened = startWith(gated, gated.openFuture(), database, serverThread);

        assertNotNull(opened, "the start path must return a session even while storage is not initialised");
        assertFalse(gated.openFuture().isDone(), "initialisation must still be blocked when start returned");
        assertTrue(gated.loadCalled.await(5, TimeUnit.SECONDS), "the initial load must be submitted asynchronously");
        assertFalse(gated.loadFinished.await(100, TimeUnit.MILLISECONDS), "the load must wait for initialisation");
        assertFalse(opened.ready(), "storage must not be ready before initialisation and the load");
        assertFalse(ClaimStorageLifecycle.ready(), "the gameplay gate must not open before the load");
        assertEquals(0, opened.cache().size(), "nothing may be cached before the load");
        assertEquals(0, serverThread.tasks.get(), "nothing should be reported before the load finishes");

        gated.openFuture().complete(StorageResult.success(database));

        assertTrue(gated.loadFinished.await(5, TimeUnit.SECONDS), "the load must finish once initialisation did");
        assertTrue(gated.initialised.get(), "the load must not run before initialisation succeeded");
        assertTrue(opened.load().get(5, TimeUnit.SECONDS).ok(), "the asynchronous load must succeed");
        assertTrue(opened.ready(), "readiness must flip after the initialisation and the load succeed");
        assertTrue(ClaimStorageLifecycle.ready(), "the gameplay gate must open after the load succeeds");
        assertEquals(1, opened.cache().size());
        assertTrue(opened.cache().get(new ClaimKey("minecraft:overworld", 0, 0)).isPresent());
        assertSame(gated.loadResult, opened.result());

        serverThread.runAll();
        assertTrue(serverThread.tasks.get() > 0, "the load outcome must be delivered for logging");
    }

    @Test
    void aFailedLoadIsReportedAndNeverBecomesReady() throws Exception {
        Path database = directory.resolve("failing.db");
        FailingLoadRepository repository = new FailingLoadRepository();
        RecordingExecutor serverThread = new RecordingExecutor();

        ClaimStorageSession opened = startWith(repository, repository.openFuture(), database, serverThread);

        assertNotNull(opened);
        assertTrue(repository.loadCalled.await(5, TimeUnit.SECONDS));
        assertFalse(opened.ready(), "a failed load must not look like an empty world");
        assertNotNull(opened.result(), "the failure must be reported as a result value");
        assertTrue(opened.result().failed());
        assertNull(opened.failure(), "a reported failure is not an exceptional completion");

        serverThread.runAll();
        assertTrue(serverThread.tasks.get() > 0, "the failure must be delivered for logging");
    }

    @Test
    void aSuccessfulLoadCannotMakeAFailedOpenLookReady() {
        Path database = directory.resolve("open-failed.db");
        OpenFailedRepository repository = new OpenFailedRepository();
        RecordingExecutor serverThread = new RecordingExecutor();

        ClaimStorageSession opened = startWith(repository, repository.openFuture(), database, serverThread);

        assertNotNull(opened);
        assertTrue(opened.result().ok(), "the load itself succeeded");
        assertTrue(opened.cache().ready(), "the cache was filled by the successful load");
        assertFalse(opened.ready(), "a failed open must keep the session unready even after a successful load");
    }

    @Test
    void anOpenerThatReturnsNothingLeavesTheSessionUnavailableWithoutThrowing() {
        ClaimStorageLifecycle.opener = (database, workerThreadName, delivery) -> null;

        ClaimStorageSession opened = ClaimStorageLifecycle.openWith(
                ClaimStorageLifecycle.opener, directory.resolve("missing.db"), "test-worker", Runnable::run);

        assertNull(opened, "an opener that cannot start storage must yield no session, not an exception");
    }

    @Test
    void theProductionOpenerDoesNoDiskWorkOnTheCallingThread() throws Exception {
        ClaimStorageLifecycle.opener = ClaimStorageOpener.sqlite();
        Path database = directory.resolve("nested").resolve("data").resolve("nusacore-claims.db");
        // A same-thread delivery keeps the load future independent of a server thread that never runs here.
        Executor sameThread = Runnable::run;

        long startedAt = System.nanoTime();
        ClaimStorageSession opened = ClaimStorageLifecycle.start(database, sameThread);
        long openMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertNotNull(opened);
        assertTrue(openMillis < 2_000, "the real start path took " + openMillis + " ms on the caller's thread");

        try {
            opened.load().get(15, TimeUnit.SECONDS);
            assertTrue(opened.ready(), "the real asynchronous open and load must become ready");
            assertTrue(Files.isRegularFile(database), "the worker must have created the database file");
            assertNull(opened.failure());
        } finally {
            ClaimStorageLifecycle.close();
        }
    }

    /** Starts through the lifecycle with the given opener, keeping the session published like a server start would. */
    private ClaimStorageSession startWith(ClaimRepository repository,
            CompletableFuture<StorageResult<Path>> openFuture, Path database, Executor serverThread) {
        ClaimStorageOpener opener = (path, workerThreadName, delivery) ->
                new ClaimStorageSession(path, repository, openFuture, delivery);
        ClaimStorageLifecycle.opener = opener;
        return ClaimStorageLifecycle.start(database, serverThread);
    }

    /** A repository whose open future, and therefore its load, the test releases by hand. */
    private static final class GatedInitialisationRepository implements ClaimRepository {
        private final CompletableFuture<StorageResult<Path>> openFuture = new CompletableFuture<>();
        private final CountDownLatch loadCalled = new CountDownLatch(1);
        private final CountDownLatch loadFinished = new CountDownLatch(1);
        private final AtomicBoolean initialised = new AtomicBoolean();
        private final Claim claim = Claim.created(
                new ClaimKey("minecraft:overworld", 0, 0), ClaimOwner.player(OWNER), CREATED);
        private final StorageResult<ClaimSnapshot> loadResult =
                StorageResult.success(new ClaimSnapshot(List.of(claim), List.of()));

        private CompletableFuture<StorageResult<Path>> openFuture() {
            return openFuture;
        }

        @Override
        public CompletableFuture<StorageResult<Claim>> find(ClaimKey key) {
            return CompletableFuture.completedFuture(StorageResult.failure(StorageFailure.NOT_FOUND, "not used"));
        }

        @Override
        public CompletableFuture<StorageResult<ClaimSnapshot>> loadAll() {
            loadCalled.countDown();
            return openFuture.thenApply(opened -> {
                if (opened.failed()) {
                    return StorageResult.failure(opened.failureOrNull(), opened.detail());
                }
                initialised.set(true);
                loadFinished.countDown();
                return loadResult;
            });
        }

        @Override
        public CompletableFuture<StorageResult<Void>> create(Claim claim) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public CompletableFuture<StorageResult<Void>> update(Claim claim) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public CompletableFuture<StorageResult<Void>> delete(ClaimKey key) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public void close() {
        }
    }

    /** A repository whose load reports a storage failure. */
    private static final class FailingLoadRepository implements ClaimRepository {
        private final CompletableFuture<StorageResult<Path>> openFuture =
                CompletableFuture.completedFuture(StorageResult.success(Path.of("unused")));
        private final CountDownLatch loadCalled = new CountDownLatch(1);

        private CompletableFuture<StorageResult<Path>> openFuture() {
            return openFuture;
        }

        @Override
        public CompletableFuture<StorageResult<Claim>> find(ClaimKey key) {
            return CompletableFuture.completedFuture(StorageResult.failure(StorageFailure.NOT_FOUND, "not used"));
        }

        @Override
        public CompletableFuture<StorageResult<ClaimSnapshot>> loadAll() {
            loadCalled.countDown();
            return CompletableFuture.completedFuture(StorageResult.failure(StorageFailure.FAILURE, "disk gone"));
        }

        @Override
        public CompletableFuture<StorageResult<Void>> create(Claim claim) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public CompletableFuture<StorageResult<Void>> update(Claim claim) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public CompletableFuture<StorageResult<Void>> delete(ClaimKey key) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public void close() {
        }
    }

    /** A repository whose open failed but whose load still reports success, to prove readiness checks both. */
    private static final class OpenFailedRepository implements ClaimRepository {
        private final CompletableFuture<StorageResult<Path>> openFuture = CompletableFuture.completedFuture(
                StorageResult.failure(StorageFailure.FAILURE, "disk gone"));
        private final Claim claim = Claim.created(
                new ClaimKey("minecraft:overworld", 0, 0), ClaimOwner.player(OWNER), CREATED);

        private CompletableFuture<StorageResult<Path>> openFuture() {
            return openFuture;
        }

        @Override
        public CompletableFuture<StorageResult<Claim>> find(ClaimKey key) {
            return CompletableFuture.completedFuture(StorageResult.failure(StorageFailure.NOT_FOUND, "not used"));
        }

        @Override
        public CompletableFuture<StorageResult<ClaimSnapshot>> loadAll() {
            return CompletableFuture.completedFuture(
                    StorageResult.success(new ClaimSnapshot(List.of(claim), List.of())));
        }

        @Override
        public CompletableFuture<StorageResult<Void>> create(Claim claim) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public CompletableFuture<StorageResult<Void>> update(Claim claim) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public CompletableFuture<StorageResult<Void>> delete(ClaimKey key) {
            return CompletableFuture.completedFuture(StorageResult.done());
        }

        @Override
        public void close() {
        }
    }

    /** An executor that records tasks and runs them only when the test asks, standing in for the server thread. */
    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> pending = new CopyOnWriteArrayList<>();
        private final AtomicInteger tasks = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            tasks.incrementAndGet();
            pending.add(command);
        }

        private void runAll() {
            for (Runnable task : pending) {
                task.run();
            }
            pending.clear();
        }
    }
}
