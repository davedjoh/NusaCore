package com.dave.nusacore.core.storage.sqlite;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.claim.ClaimOwner;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opening and shutdown semantics: the asynchronous open does no disk work on the caller, readiness follows the load,
 * and a close either drains every accepted operation or answers it with {@link StorageFailure#CLOSED}.
 *
 * <p>The blocked-worker test is the contract that matters: a wedged disk must not leave a caller holding a future
 * that never completes, and a queued write that is answered as closed must not have been applied.
 */
class SqliteClaimRepositoryShutdownTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final String WORKER = "nusacore-claim-shutdown-test";

    @TempDir
    Path directory;

    @Test
    void openingAnUncreatablePathFailsAsynchronouslyAndLeavesStorageUnready() throws Exception {
        Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "a file where the database directory should be");
        Path database = blocker.resolve("nusacore-claims.db");

        // The call itself must not throw: a broken directory is reported through openFuture, not at the caller.
        SqliteClaimRepository repository = SqliteClaimRepository.openAsync(database, WORKER);
        try {
            StorageResult<Path> opened = await(repository.openFuture());
            assertTrue(opened.failed(), "a path under a regular file cannot be opened");
            assertFalse(repository.ready(), "a database that never opened must never look ready");
            assertTrue(repository.isClosed(),
                    "a failed initialisation must stop storage before the failure is reported");

            StorageResult<ClaimRepository.ClaimSnapshot> load = await(repository.loadAll());
            assertTrue(load.failed(), "a load against a database that never opened must fail");
            assertEquals(StorageFailure.CLOSED, load.failureOrNull(),
                    "a load submitted after a failed initialisation must be answered as closed");
        } finally {
            repository.close();
        }
    }

    @Test
    void aLoadAcceptedBeforeAFailedInitialisationIsAnsweredBeforeTheOpenCompletes() throws Exception {
        Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "a file where the database directory should be");

        // Queued immediately, so it is already accepted and sits behind the initialisation that is about to fail.
        SqliteClaimRepository repository = SqliteClaimRepository.openAsync(
                blocker.resolve("nusacore-claims.db"), WORKER);
        try {
            CompletableFuture<StorageResult<ClaimRepository.ClaimSnapshot>> load = repository.loadAll();

            StorageResult<Path> opened = await(repository.openFuture());
            assertTrue(opened.failed());
            assertTrue(load.isDone(), "the load accepted behind a failed initialisation must be answered");
            assertEquals(StorageFailure.CLOSED, await(load).failureOrNull());
            assertTrue(repository.isClosed(), "the reported failure must leave storage stopped, not half open");
        } finally {
            repository.close();
        }
    }

    @Test
    void openAsyncReturnsBeforeABlockedInitialisation() throws Exception {
        Path database = directory.resolve("gated.db");
        CountDownLatch gate = new CountDownLatch(1);
        SqliteClaimRepository repository =
                SqliteClaimRepository.openAsyncWithInitialisationGate(database, WORKER, gate);
        try {
            assertFalse(repository.openFuture().isDone(),
                    "openAsync must return before the directory, connection, and schema work runs");
            assertFalse(repository.ready(), "a blocked initialisation must not look ready");

            gate.countDown();

            assertTrue(await(repository.openFuture()).ok(), "the queued initialisation must succeed after the gate");
            assertTrue(await(repository.loadAll()).ok());
            assertTrue(repository.ready(), "readiness follows the asynchronous initialisation and load");
            assertTrue(Files.isRegularFile(database), "the worker must create the database file");
        } finally {
            gate.countDown();
            repository.close();
        }
    }

    @Test
    void readinessFollowsTheInitialLoadAndNotTheOpenAlone() throws Exception {
        Path database = directory.resolve("claims.db");

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(database, WORKER)) {
            assertFalse(repository.ready(), "opening alone must not make the stored set readable");

            StorageResult<ClaimRepository.ClaimSnapshot> loaded = await(repository.loadAll());

            assertTrue(loaded.ok());
            assertTrue(repository.ready(), "a successful load makes the whole stored set readable");
        }
    }

    @Test
    void aFailedLoadLeavesTheRepositoryUnready() throws Exception {
        SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER);
        repository.close();

        StorageResult<ClaimRepository.ClaimSnapshot> load = await(repository.loadAll());

        assertEquals(StorageFailure.CLOSED, load.failureOrNull());
        assertFalse(repository.ready(), "a failed load must not look like an empty world");
    }

    @Test
    void closeDrainsEveryAcceptedWriteBeforeItReturns() throws Exception {
        Path database = directory.resolve("claims.db");
        List<CompletableFuture<StorageResult<Void>>> writes = new ArrayList<>();
        List<Claim> expected = new ArrayList<>();
        SqliteClaimRepository repository = SqliteClaimRepository.open(database, WORKER);
        for (int i = 0; i < 40; i++) {
            Claim claim = Claim.created(new ClaimKey("minecraft:overworld", i, -i), ClaimOwner.player(OWNER), CREATED);
            expected.add(claim);
            writes.add(repository.create(claim));
        }

        repository.close();

        for (int i = 0; i < writes.size(); i++) {
            CompletableFuture<StorageResult<Void>> write = writes.get(i);
            assertTrue(write.isDone(), "accepted write " + i + " must be complete when close returns");
            assertTrue(write.get(0, TimeUnit.SECONDS).ok(), "a drained write must succeed: " + expected.get(i).key());
        }

        try (SqliteClaimRepository reopened = SqliteClaimRepository.open(database, WORKER)) {
            ClaimRepository.ClaimSnapshot snapshot = await(reopened.loadAll()).valueOrNull();
            assertNotNull(snapshot);
            assertEquals(40, snapshot.claims().size(), "every drained write must be durable");
        }
    }

    @Test
    void aBlockedWorkerCannotLeaveQueuedFuturesIncomplete() throws Exception {
        Path database = directory.resolve("claims.db");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        SqliteClaimRepository repository =
                SqliteClaimRepository.openWithBlockedWorker(database, WORKER, 1, release, exited);
        CompletableFuture<StorageResult<Void>> first;
        CompletableFuture<StorageResult<Void>> second;
        try {
            first = repository.create(Claim.created(new ClaimKey("minecraft:overworld", 0, 0),
                    ClaimOwner.player(OWNER), CREATED));
            second = repository.create(Claim.created(new ClaimKey("minecraft:overworld", 1, 1),
                    ClaimOwner.player(OWNER), CREATED));

            long startedAt = System.nanoTime();
            repository.close();
            long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(closeMillis < 5_000, "a close with a 1 s drain must not wait for the busy timeout: "
                    + closeMillis + " ms");
            assertTrue(first.isDone(), "an accepted write must never be left without an answer");
            assertTrue(second.isDone(), "a queued write must never be left without an answer");
            assertEquals(StorageFailure.CLOSED, first.get(1, TimeUnit.SECONDS).failureOrNull(),
                    "a write the blocked worker never ran is answered as closed, not silently dropped");
            assertEquals(StorageFailure.CLOSED, second.get(1, TimeUnit.SECONDS).failureOrNull());
        } finally {
            release.countDown();
        }

        assertTrue(exited.await(5, TimeUnit.SECONDS), "the blocked worker must finish once it is released");
        try (SqliteClaimRepository reopened = SqliteClaimRepository.open(database, WORKER)) {
            ClaimRepository.ClaimSnapshot snapshot = await(reopened.loadAll()).valueOrNull();
            assertNotNull(snapshot);
            assertEquals(0, snapshot.claims().size(),
                    "a queued write answered CLOSED before it ran must never be applied afterwards");
        }
    }

    @Test
    void everyAcceptedOperationIsAnsweredWhenCloseRacesSubmissions() throws Exception {
        Path database = directory.resolve("racing.db");
        SqliteClaimRepository repository = SqliteClaimRepository.open(database, WORKER);
        List<CompletableFuture<StorageResult<Void>>> writes = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Thread> submitters = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);

        for (int thread = 0; thread < 4; thread++) {
            int base = thread * 25;
            Thread submitter = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 25; i++) {
                    Claim claim = Claim.created(new ClaimKey("minecraft:overworld", base + i, -base - i),
                            ClaimOwner.player(OWNER), CREATED);
                    writes.add(repository.create(claim));
                }
            }, "claim-submitter-" + thread);
            submitters.add(submitter);
            submitter.start();
        }
        Thread closer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            repository.close();
        }, "claim-closer");
        closer.start();
        start.countDown();

        for (Thread submitter : submitters) {
            submitter.join(10_000);
            assertFalse(submitter.isAlive(), "a submitter must not hang behind a closing repository");
        }
        closer.join(15_000);
        assertFalse(closer.isAlive(), "close must return even while submissions are racing it");

        assertEquals(100, writes.size(), "every submission must have produced a future");
        for (int i = 0; i < writes.size(); i++) {
            CompletableFuture<StorageResult<Void>> write = writes.get(i);
            assertTrue(write.isDone(), "accepted operation " + i + " must be complete when close returns");
            StorageResult<Void> result = write.get(1, TimeUnit.SECONDS);
            assertTrue(result.ok() || result.failureOrNull() == StorageFailure.CLOSED,
                    "an accepted operation is either drained or explicitly CLOSED, not another failure: " + result);
        }
    }

    /** Waits for a storage future, turning an unexpected exceptional completion into a test failure. */
    private static <T> StorageResult<T> await(CompletableFuture<StorageResult<T>> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException unexpected) {
            throw new AssertionError("a storage operation completed exceptionally", unexpected.getCause());
        }
    }
}
