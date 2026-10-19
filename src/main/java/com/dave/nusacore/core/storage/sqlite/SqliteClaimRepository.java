package com.dave.nusacore.core.storage.sqlite;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.storage.ClaimCodec;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The SQLite claim repository.
 *
 * <p>All JDBC work runs on one dedicated daemon thread, and every method returns immediately with a future, so the
 * server tick thread never opens a connection, waits for a lock, or writes a page. One worker is enough: SQLite
 * serialises writers anyway, and a single worker runs work in submission order, so the initial load always finishes
 * before any write submitted after it, without extra coordination.
 *
 * <p>Failures are values. A duplicate key becomes {@link StorageFailure#DUPLICATE_KEY}, a missing row becomes
 * {@link StorageFailure#NOT_FOUND}, an unreadable row becomes {@link StorageFailure#CORRUPT_ROW} on a single read or
 * is listed in the snapshot on a load, and any other driver problem becomes {@link StorageFailure#FAILURE}. The future
 * completes normally in all of those cases, so a caller can never receive an exception on the game thread. Only an
 * {@link Error} completes it exceptionally, because a failing JVM is not a storage answer.
 *
 * <p>Each write is one transaction covering the claim row and all of its trusted rows, so a claim is never stored
 * with half its trust list, and a completed future means the data has been written and flushed.
 *
 * <h2>Opening and readiness</h2>
 *
 * <p>{@link #openAsync(Path, String)} does no filesystem, JDBC, or schema work on the calling thread. The directory,
 * the connection, and the schema pass all run as the first task on the storage worker, so a caller on a server
 * lifecycle thread returns at once. Calls made meanwhile queue behind that task. The repository reports
 * {@link #ready()} only after the schema pass succeeded and {@link #loadAll()} read the stored claims;
 * {@link #openFuture()} carries the initialisation outcome, so a caller can log a broken directory or schema without
 * inspecting an operation result.
 *
 * <h2>Shutdown</h2>
 *
 * <p>{@link #close()} drains. Acceptance and submission happen under the same lock the close uses, so every operation
 * accepted before the close was requested is already in the worker's queue and is allowed to finish; each of their
 * futures is complete before {@code close()} returns. Only when the worker does not drain within
 * {@link #CLOSE_TIMEOUT_SECONDS} does the close interrupt it and answer the remaining futures with
 * {@link StorageFailure#CLOSED}. A queued operation answered that way is removed from the accepted set before it can
 * start, so it never runs after its caller was told nothing was written. SQLite rolls back an interrupted transaction.
 * A worker that ignores its interrupt is abandoned as a daemon thread, and the one unavoidable limit is that a write
 * it was already running may commit after the timeout while its caller was told {@link StorageFailure#CLOSED}; no
 * caller is left with an incomplete future, and no operation is silently dropped without an answer.
 */
public final class SqliteClaimRepository implements ClaimRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger("nusacore");

    /**
     * How long a close lets accepted work finish before it interrupts the worker.
     *
     * <p>A SQLite write waits at most the busy timeout ({@link SqliteDataSourceFactory#BUSY_TIMEOUT_MILLIS}) for a
     * lock, so draining a normal queue finishes well inside this bound. The limit exists for a wedged disk, not for
     * ordinary traffic.
     */
    static final long CLOSE_TIMEOUT_SECONDS = 10;

    private static final String CLAIM_COLUMNS =
            "dimension, chunk_x, chunk_z, owner_kind, owner_uuid, flags, created_at, expires_at";

    /** The connection factory, created on the worker during initialisation so the caller does no setup. */
    private volatile SQLiteDataSource source;

    private final ExecutorService executor;

    /** How long {@link #close()} drains before it interrupts the worker; {@link #CLOSE_TIMEOUT_SECONDS} in production. */
    private final long closeTimeoutSeconds;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();

    /**
     * Serialises accepting, starting, and cancelling operations.
     *
     * <p>An operation is added to {@link #pending} and submitted to the worker under this lock, and a close takes the
     * same lock before it shuts the worker down. There is therefore no window in which an operation has been accepted
     * but not yet submitted: a close either drains it with the executor or finds it in {@link #pending} and answers
     * it. Without that, a task could be answered as "cancelled before it ran" and still be submitted afterwards.
     */
    private final Object shutdownLock = new Object();

    /** Accepted operations that have not started yet. Guarded by {@link #shutdownLock}. */
    private final Queue<PendingTask<?>> pending = new ArrayDeque<>();

    /** The operation the worker is running now. Guarded by {@link #shutdownLock}. */
    private PendingTask<?> active;

    /** The worker thread, so a close called from it does not wait for itself. */
    private volatile Thread workerThread;

    /** What opening produced, so a caller can see the failure instead of only a closed repository. */
    private final CompletableFuture<StorageResult<Path>> openFuture = new CompletableFuture<>();

    /** Completes once the drain and cancellation in {@link #close()} are done. */
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    private SqliteClaimRepository(String threadName) {
        this(threadName, CLOSE_TIMEOUT_SECONDS);
    }

    private SqliteClaimRepository(String threadName, long closeTimeoutSeconds) {
        this.closeTimeoutSeconds = closeTimeoutSeconds;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            this.workerThread = thread;
            return thread;
        });
    }

    /** A repository that is already prepared, for the blocking {@link #open(Path, String)} only. */
    private SqliteClaimRepository(SQLiteDataSource source, String threadName, Path opened) {
        this(threadName);
        this.source = source;
        openFuture.complete(StorageResult.success(opened));
    }

    /**
     * Starts a claim repository for a database file, creating the file, its directory, the schema, and the initial
     * load on the repository's own worker.
     *
     * <p>Nothing here touches the disk. A caller on a server lifecycle thread gets the repository back immediately,
     * calls {@link #loadAll()} without blocking, and logs a broken directory, file, or schema from the future that
     * {@link #openFuture()} returns. {@link #ready()} stays false until the schema pass and the initial load have both
     * succeeded, so the store it feeds never reports an unopened database as an empty world.
     *
     * @param databaseFile the SQLite file
     * @param threadName the worker thread name, so a thread dump names the database it serves
     */
    public static SqliteClaimRepository openAsync(Path databaseFile, String threadName) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(threadName, "threadName");
        SqliteClaimRepository repository = new SqliteClaimRepository(threadName);
        repository.submitInitialisation("initialise claim storage at " + databaseFile,
                () -> repository.prepare(databaseFile));
        return repository;
    }

    /**
     * The asynchronous open with a gate in front of the initialisation, for tests that must prove the caller does not
     * wait for the schema pass.
     *
     * <p>The gate occupies the worker exactly as a slow disk would. The returned repository has not prepared anything
     * when this method returns; releasing the gate lets the queued initialisation and every later call proceed.
     */
    static SqliteClaimRepository openAsyncWithInitialisationGate(Path databaseFile, String threadName,
            CountDownLatch gate) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(threadName, "threadName");
        Objects.requireNonNull(gate, "gate");
        SqliteClaimRepository repository = new SqliteClaimRepository(threadName);
        repository.executor.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        repository.submitInitialisation("initialise claim storage at " + databaseFile,
                () -> repository.prepare(databaseFile));
        return repository;
    }

    /**
     * Opens a claim repository for a database file, creating the file, its directory, and the schema before
     * returning.
     *
     * <p>This is the blocking form, for tests and tools that have no lifecycle thread to protect. Server code uses
     * {@link #openAsync(Path, String)} instead.
     *
     * @throws SQLException when the file, the directory, or the schema cannot be created
     */
    public static SqliteClaimRepository open(Path databaseFile, String threadName) throws SQLException {
        return open(databaseFile, threadName, CLOSE_TIMEOUT_SECONDS);
    }

    /**
     * The blocking open with an explicit close timeout, so a test can exercise the interrupted-drain path without
     * waiting ten seconds.
     *
     * @param closeTimeoutSeconds how long a close drains before it interrupts the worker
     */
    static SqliteClaimRepository open(Path databaseFile, String threadName, long closeTimeoutSeconds)
            throws SQLException {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(threadName, "threadName");
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) {
            File directory = parent.toFile();
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new SQLException("cannot create the claim database directory: " + directory);
            }
        }
        SQLiteDataSource source = SqliteDataSourceFactory.create(databaseFile);
        try (Connection connection = source.getConnection()) {
            ClaimsSchema.apply(connection);
        }
        SqliteClaimRepository repository = new SqliteClaimRepository(threadName, closeTimeoutSeconds);
        repository.source = source;
        repository.openFuture.complete(StorageResult.success(databaseFile));
        return repository;
    }

    /**
     * A prepared repository whose worker is occupied by {@code gate}, for the shutdown test only.
     *
     * <p>Real disk work never blocks for a test's convenience, so a blocked worker has to be staged. The gate holds the
     * worker exactly as a wedged disk would, while the schema is already prepared and later operations are real ones
     * against a real file.
     */
    static SqliteClaimRepository openWithBlockedWorker(Path databaseFile, String threadName, long closeTimeoutSeconds,
            java.util.concurrent.CountDownLatch gate, java.util.concurrent.CountDownLatch exited) throws SQLException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(exited, "exited");
        SqliteClaimRepository repository = open(databaseFile, threadName, closeTimeoutSeconds);
        repository.executor.execute(() -> {
            try {
                while (true) {
                    try {
                        gate.await();
                        return;
                    } catch (InterruptedException interrupted) {
                        // A close interrupts the worker. Wait again so the test proves that an operation the worker
                        // cannot finish is still answered, rather than relying on the interrupt ending it.
                    }
                }
            } finally {
                exited.countDown();
            }
        });
        return repository;
    }

    /**
     * The outcome of the asynchronous open: the database path once the directory, the file, and the schema are ready,
     * or a failure when any of them could not be prepared.
     */
    public CompletableFuture<StorageResult<Path>> openFuture() {
        return openFuture;
    }

    /** Creates the directory, the connection, and the schema. Runs on the worker, never on the caller's thread. */
    private StorageResult<Path> prepare(Path databaseFile) throws SQLException {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) {
            File directory = parent.toFile();
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new SQLException("cannot create the claim database directory: " + directory);
            }
        }
        SQLiteDataSource prepared = SqliteDataSourceFactory.create(databaseFile);
        try (Connection connection = prepared.getConnection()) {
            ClaimsSchema.apply(connection);
        }
        this.source = prepared;
        return StorageResult.success(databaseFile);
    }

    /**
     * True once the schema pass succeeded and {@link #loadAll()} finished, so a cache filled from this repository
     * describes the whole stored set. Until then gameplay must not treat a cache miss as unclaimed.
     */
    public boolean ready() {
        return ready.get();
    }

    @Override
    public CompletableFuture<StorageResult<Claim>> find(ClaimKey key) {
        Objects.requireNonNull(key, "key");
        return submit("find " + key, () -> findNow(key), false);
    }

    /**
     * Reads every stored claim.
     *
     * <p>A successful read marks the repository {@link #ready()}, because a caller that has the whole stored set no
     * longer needs to know whether the schema pass is still running. An unsuccessful one leaves it unready, so a
     * failed load never looks like an empty world.
     */
    @Override
    public CompletableFuture<StorageResult<ClaimSnapshot>> loadAll() {
        return submit("load all claims", this::loadAllNow, true);
    }

    @Override
    public CompletableFuture<StorageResult<Void>> create(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return submit("create " + claim.key(), () -> write(claim, WriteMode.INSERT), false);
    }

    @Override
    public CompletableFuture<StorageResult<Void>> update(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return submit("update " + claim.key(), () -> write(claim, WriteMode.UPDATE), false);
    }

    @Override
    public CompletableFuture<StorageResult<Void>> delete(ClaimKey key) {
        Objects.requireNonNull(key, "key");
        return submit("delete " + key, () -> deleteNow(key), false);
    }

    /**
     * Stops accepting work, drains what was already accepted, and releases the worker.
     *
     * <p>Accepted operations run in submission order, so the drain lets all of them finish, and every future this
     * repository handed out is complete when this method returns. After {@link #CLOSE_TIMEOUT_SECONDS} the worker is
     * interrupted, the queued operations are answered as {@link StorageFailure#CLOSED} and removed before they can
     * start, and the operation the worker was running is answered too, so a wedged disk cannot leave a caller
     * waiting.
     *
     * <p>SQLite needs no explicit close here because each operation owns and closes its connection.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            if (Thread.currentThread() != workerThread) {
                awaitStopped();
            }
            return;
        }
        try {
            // A close invoked from the storage worker itself cannot wait for that worker to terminate. The only
            // production path that does this is a failed initialisation, but the guard keeps a future caller from
            // turning close into a ten-second self-wait.
            stop(Thread.currentThread() == workerThread, true);
        } finally {
            stopped.complete(null);
        }
    }

    /** True once {@link #close()} ran; later operations fail with {@link StorageFailure#CLOSED}. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Waits for a close that another thread already started, so a repeated close still returns after the drain. */
    private void awaitStopped() {
        try {
            stopped.get(closeTimeoutSeconds * 2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The other close is still finishing a wedged worker; it has already answered every future it owns.
        }
    }

    /**
     * Stops accepting work, drains the queue, and answers what cannot finish.
     *
     * <p>The lock is held while the executor is shut down, so every operation that was accepted has already been
     * submitted. From then on no new operation can be accepted, and the drain covers the whole accepted set.
     *
     * @param fromWorker whether this runs on the storage worker itself, which therefore cannot be waited for
     * @param cancelActive whether the operation the worker is running should be answered as CLOSED; false when the
     *        caller is that operation and is about to report its own real outcome (a failed initialisation)
     */
    private void stop(boolean fromWorker, boolean cancelActive) {
        synchronized (shutdownLock) {
            closed.set(true);
            executor.shutdown();
        }
        boolean drained = true;
        if (!fromWorker) {
            try {
                drained = executor.awaitTermination(closeTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                drained = false;
            }
            if (!drained) {
                // Interrupt the running operation, so an in-flight transaction is rolled back rather than left to
                // finish while the queue is already being answered. Queued work is returned by shutdownNow and is
                // also still in pending; cancel() is idempotent, so it is answered exactly once.
                executor.shutdownNow();
            }
        }
        // A failed initialisation stops the worker from inside its own task and is about to report FAILURE; that task
        // must keep its real result. A close invoked from a task is different: the caller asked for shutdown, so its
        // future is answered as CLOSED like every other unfinished operation.
        List<PendingTask<?>> abandoned = takeUnfinished(!fromWorker || cancelActive);
        if (!abandoned.isEmpty()) {
            String reason;
            if (fromWorker) {
                reason = "were left over after the worker stopped accepting";
            } else if (drained) {
                reason = "were still queued after the worker drained";
            } else {
                reason = "did not finish within " + closeTimeoutSeconds + "s";
            }
            LOGGER.warn("Claim storage stopped before it drained; {} accepted operation(s) {} and are answered as"
                    + " CLOSED. A queued operation answered this way never ran. An operation that was already running"
                    + " may still have committed before the interrupt landed; that is the one durability limit of a"
                    + " timed-out close, and its caller was told CLOSED rather than left waiting",
                    abandoned.size(), reason);
        }
        for (PendingTask<?> task : abandoned) {
            task.cancel();
        }
    }

    /**
     * Removes everything still waiting from the accepted set and returns it, so no close path can leave an accepted
     * operation unaccounted for.
     *
     * <p>Called after the worker was shut down (or after the worker itself stopped accepting), so a queued task
     * cannot start while this runs. The active task is included because an interrupted worker may ignore the
     * interrupt; its future must still complete.
     */
    private List<PendingTask<?>> takeUnfinished(boolean includeActive) {
        synchronized (shutdownLock) {
            List<PendingTask<?>> unfinished = new ArrayList<>(pending);
            pending.clear();
            if (includeActive && active != null && !active.isDone() && !unfinished.contains(active)) {
                unfinished.add(active);
            }
            return unfinished;
        }
    }

    private StorageResult<Claim> findNow(ClaimKey key) throws SQLException {
        try (Connection connection = source.getConnection()) {
            Long id = findId(connection, key);
            if (id == null) {
                return StorageResult.failure(StorageFailure.NOT_FOUND, "no claim at " + key);
            }
            ClaimCodec.Row row;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + CLAIM_COLUMNS + " FROM claims WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return StorageResult.failure(StorageFailure.NOT_FOUND, "no claim at " + key);
                    }
                    row = readRow(result);
                }
            }
            ClaimCodec.Decoded decoded = ClaimCodec.decode(row, readTrusted(connection, id));
            return switch (decoded) {
                case ClaimCodec.Decoded.Valid valid -> StorageResult.success(valid.claim());
                case ClaimCodec.Decoded.Invalid invalid ->
                        StorageResult.failure(StorageFailure.CORRUPT_ROW, key + ": " + invalid.reason());
            };
        }
    }

    private StorageResult<ClaimSnapshot> loadAllNow() throws SQLException {
        List<Claim> claims = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try (Connection connection = source.getConnection()) {
            // Read each table in one statement and join in memory, so the snapshot needs two queries whatever the
            // claim count is, and a row that cannot be read never fails the whole load.
            List<IdRow> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT id, " + CLAIM_COLUMNS + " FROM claims ORDER BY dimension, chunk_x, chunk_z")) {
                while (result.next()) {
                    rows.add(new IdRow(result.getLong("id"), readRow(result)));
                }
            }
            Map<Long, List<ClaimCodec.TrustedRow>> trustedByClaim = readAllTrusted(connection);
            for (IdRow row : rows) {
                ClaimCodec.Decoded decoded =
                        ClaimCodec.decode(row.row(), trustedByClaim.getOrDefault(row.id(), List.of()));
                switch (decoded) {
                    case ClaimCodec.Decoded.Valid valid -> claims.add(valid.claim());
                    case ClaimCodec.Decoded.Invalid invalid -> unreadable.add("claim row " + row.id() + " ("
                            + row.row().dimension() + " " + row.row().chunkX() + "," + row.row().chunkZ()
                            + "): " + invalid.reason());
                }
            }
        }
        return StorageResult.success(new ClaimSnapshot(claims, unreadable));
    }

    private StorageResult<Void> write(Claim claim, WriteMode mode) throws SQLException {
        ClaimCodec.Row row = ClaimCodec.encode(claim);
        List<ClaimCodec.TrustedRow> trusted = ClaimCodec.encodeTrusted(claim);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            boolean creating = false;
            try {
                Long id = findId(connection, claim.key());
                if (mode == WriteMode.INSERT && id != null) {
                    connection.rollback();
                    return StorageResult.failure(
                            StorageFailure.DUPLICATE_KEY, "claim already exists: " + claim.key());
                }
                if (mode == WriteMode.UPDATE && id == null) {
                    connection.rollback();
                    return StorageResult.failure(StorageFailure.NOT_FOUND, "claim is gone: " + claim.key());
                }
                creating = id == null;
                long claimId = creating ? insertClaim(connection, row) : updateClaim(connection, id, row);
                replaceTrusted(connection, claimId, trusted);
                connection.commit();
                return StorageResult.done();
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                if (creating && isConstraintViolation(failure)) {
                    // The unique key was taken between the lookup and the insert, by a writer outside this process.
                    return StorageResult.failure(
                            StorageFailure.DUPLICATE_KEY, "claim already exists: " + claim.key());
                }
                throw failure;
            }
        }
    }

    private StorageResult<Void> deleteNow(ClaimKey key) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // ON DELETE CASCADE removes the trusted rows; foreign keys are enabled for every connection.
                int removed;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM claims WHERE dimension = ? AND chunk_x = ? AND chunk_z = ?")) {
                    bindKey(statement, key);
                    removed = statement.executeUpdate();
                }
                if (removed == 0) {
                    connection.rollback();
                    return StorageResult.failure(StorageFailure.NOT_FOUND, "no claim at " + key);
                }
                connection.commit();
                return StorageResult.done();
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                throw failure;
            }
        }
    }

    private static Long findId(Connection connection, ClaimKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM claims WHERE dimension = ? AND chunk_x = ? AND chunk_z = ?")) {
            bindKey(statement, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private static long insertClaim(Connection connection, ClaimCodec.Row row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO claims (" + CLAIM_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bindRow(statement, row);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("the database did not return the new claim id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static long updateClaim(Connection connection, long id, ClaimCodec.Row row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE claims SET dimension = ?, chunk_x = ?, chunk_z = ?, owner_kind = ?, owner_uuid = ?,"
                        + " flags = ?, created_at = ?, expires_at = ? WHERE id = ?")) {
            bindRow(statement, row);
            statement.setLong(9, id);
            statement.executeUpdate();
        }
        return id;
    }

    private static void replaceTrusted(Connection connection, long id, List<ClaimCodec.TrustedRow> trusted)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM claim_trusted WHERE claim_id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
        if (trusted.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO claim_trusted (claim_id, player_uuid, permissions) VALUES (?, ?, ?)")) {
            for (ClaimCodec.TrustedRow row : trusted) {
                insert.setLong(1, id);
                insert.setString(2, row.playerUuid());
                insert.setString(3, row.permissions());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<ClaimCodec.TrustedRow> readTrusted(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, permissions FROM claim_trusted WHERE claim_id = ? ORDER BY player_uuid")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                List<ClaimCodec.TrustedRow> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new ClaimCodec.TrustedRow(result.getString(1), result.getString(2)));
                }
                return rows;
            }
        }
    }

    private static Map<Long, List<ClaimCodec.TrustedRow>> readAllTrusted(Connection connection) throws SQLException {
        Map<Long, List<ClaimCodec.TrustedRow>> byClaim = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT claim_id, player_uuid, permissions FROM claim_trusted"
                                + " ORDER BY claim_id, player_uuid")) {
            while (result.next()) {
                byClaim.computeIfAbsent(result.getLong(1), ignored -> new ArrayList<>())
                        .add(new ClaimCodec.TrustedRow(result.getString(2), result.getString(3)));
            }
        }
        return byClaim;
    }

    private static ClaimCodec.Row readRow(ResultSet result) throws SQLException {
        return new ClaimCodec.Row(
                result.getString("dimension"),
                result.getInt("chunk_x"),
                result.getInt("chunk_z"),
                result.getString("owner_kind"),
                result.getString("owner_uuid"),
                result.getString("flags"),
                result.getString("created_at"),
                result.getString("expires_at"));
    }

    private static void bindRow(PreparedStatement statement, ClaimCodec.Row row) throws SQLException {
        statement.setString(1, row.dimension());
        statement.setInt(2, row.chunkX());
        statement.setInt(3, row.chunkZ());
        statement.setString(4, row.ownerKind());
        statement.setString(5, row.ownerUuid());
        statement.setString(6, row.flags());
        statement.setString(7, row.createdAt());
        statement.setString(8, row.expiresAt());
    }

    private static void bindKey(PreparedStatement statement, ClaimKey key) throws SQLException {
        statement.setString(1, key.dimension());
        statement.setInt(2, key.chunkX());
        statement.setInt(3, key.chunkZ());
    }

    /**
     * True when the driver reported a unique or primary-key violation.
     *
     * <p>The extended SQLite result code carries the primary result in its low byte, so a unique-column violation and
     * a primary-key violation are both recognised without depending on the message text.
     */
    private static boolean isConstraintViolation(SQLException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof org.sqlite.SQLiteException sqlite) {
                int primary = sqlite.getResultCode().code & 0xFF;
                return primary == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT.code;
            }
        }
        return false;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            LOGGER.debug("Claim storage rollback failed", rollbackFailure);
        }
    }

    /** Submits one storage operation to the worker and turns its outcome into a result value. */
    private <T> CompletableFuture<StorageResult<T>> submit(String what, StorageOperation<T> operation,
            boolean marksReady) {
        CompletableFuture<StorageResult<T>> future = new CompletableFuture<>();
        if (closed.get()) {
            future.complete(StorageResult.failure(StorageFailure.CLOSED, what));
            return future;
        }
        PendingTask<T> task = new PendingTask<T>(what, () -> {
            if (source == null) {
                // Only reachable if a close raced the submission; the initialisation itself never takes this branch.
                return StorageResult.failure(StorageFailure.FAILURE, what + ": storage was never opened");
            }
            StorageResult<T> result = operation.run();
            if (marksReady && result.ok()) {
                ready.set(true);
            }
            return result;
        }, future, null);
        if (!accept(task)) {
            future.complete(StorageResult.failure(StorageFailure.CLOSED, what));
        }
        return future;
    }

    /**
     * Starts the repository: runs the directory, connection, and schema pass as the first accepted operation.
     *
     * <p>The initialisation sits in the same queue as every later call, so it runs first and a close waits for it
     * exactly as it waits for a write. A failure stops the repository, because nothing above can use a store without
     * a schema.
     */
    private void submitInitialisation(String what, StorageOperation<Path> operation) {
        PendingTask<Path> task = new PendingTask<>(what, operation, openFuture,
                this::stopAfterFailedInitialisation);
        if (!accept(task)) {
            openFuture.complete(StorageResult.failure(StorageFailure.CLOSED, what));
        }
    }

    /**
     * Stops the repository when the schema pass did not succeed, whether it reported a failure or threw.
     *
     * <p>Runs on the worker, so it cannot wait for the worker to terminate. Calls accepted behind the failed
     * initialisation are answered as closed instead of running against a database that was never prepared. A close
     * that already ran wins, so this cannot turn a drained shutdown into an abandoned one.
     */
    private void stopAfterFailedInitialisation(StorageResult<?> result) {
        if (result.ok() || !closed.compareAndSet(false, true)) {
            return;
        }
        try {
            stop(true, false);
        } finally {
            stopped.complete(null);
        }
    }

    /**
     * Accepts one operation and hands it to the worker, unless the repository is closing.
     *
     * <p>Adding to {@link #pending} and submitting happen under the same lock a close uses. An operation is therefore
     * never left in the gap between "accepted" and "submitted": the close either lets the worker drain it or answers
     * it as CLOSED, but it cannot do both to a task that never reached the executor.
     *
     * @return false when the repository is already closing; the caller answers its own future
     */
    private boolean accept(PendingTask<?> task) {
        synchronized (shutdownLock) {
            if (closed.get()) {
                return false;
            }
            pending.add(task);
            try {
                executor.execute(task);
            } catch (RejectedExecutionException rejected) {
                // The executor refused it, so it never entered the worker queue and will never run.
                pending.remove(task);
                task.cancelBeforeRun();
            }
            return true;
        }
    }

    /**
     * One accepted operation, its description, and the future that must not be left hanging.
     *
     * <p>{@link #cancel()} is the only path that completes a future outside the worker. It answers an operation that
     * was accepted but will not finish, either because it never ran or because the worker ignored its interrupt.
     */
    private final class PendingTask<T> implements Runnable {
        private final String what;
        private final StorageOperation<T> operation;
        private final CompletableFuture<StorageResult<T>> future;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean started;

        /**
         * Runs on the worker after this task reported its outcome.
         *
         * <p>The initialisation uses it, so a schema failure stops storage whichever way the task ended, and it runs
         * before the task's future completes. It is not gated on {@link #closed}, because a close happening to race
         * the task must not hide the failure.
         */
        private final AfterOutcome afterwards;

        private PendingTask(String what, StorageOperation<T> operation,
                CompletableFuture<StorageResult<T>> future, AfterOutcome afterwards) {
            this.what = what;
            this.operation = operation;
            this.future = future;
            this.afterwards = afterwards;
        }

        @Override
        public void run() {
            synchronized (shutdownLock) {
                pending.remove(this);
                if (cancelled.get() || future.isDone()) {
                    // A close answered this task before it could start; running it would write after telling the
                    // caller nothing was written.
                    return;
                }
                active = this;
                started = true;
            }
            StorageResult<T> result;
            try {
                result = runOperation();
            } catch (Error fatal) {
                future.completeExceptionally(fatal);
                clearActive();
                throw fatal;
            }
            try {
                AfterOutcome after = afterwards;
                if (after != null) {
                    // Before the future completes, so a caller that sees a failed open can never slip a new
                    // operation into a repository that is about to stop.
                    after.run(result);
                }
                future.complete(result);
            } finally {
                // Kept active until the future is complete, so a close that times out cannot return while this
                // accepted operation still has an incomplete future. A close from this worker skips the active task
                // instead, so a failed initialisation keeps its real failure result.
                clearActive();
            }
        }

        /** True when this task's future already has an answer. */
        private boolean isDone() {
            return future.isDone();
        }

        private void clearActive() {
            synchronized (shutdownLock) {
                if (active == this) {
                    active = null;
                }
            }
        }

        private StorageResult<T> runOperation() {
            try {
                return operation.run();
            } catch (SQLException failure) {
                if (closed.get() && Thread.currentThread().isInterrupted()) {
                    // A stop interrupted this operation. SQLite rolls back a transaction interrupted this way, so
                    // the honest answer is CLOSED rather than a driver failure a caller might retry as transient.
                    LOGGER.warn("Claim storage interrupted while it tried to {}; the operation was not applied", what,
                            failure);
                    return StorageResult.failure(StorageFailure.CLOSED,
                            what + ": interrupted by shutdown; nothing was written");
                }
                LOGGER.warn("Claim storage could not {}", what, failure);
                return StorageResult.failure(StorageFailure.FAILURE, what + ": " + failure.getMessage());
            } catch (RuntimeException failure) {
                LOGGER.error("Claim storage could not {} because of an unexpected error", what, failure);
                return StorageResult.failure(StorageFailure.FAILURE, what + ": " + failure);
            }
        }

        /** Answers an operation the worker will never finish, so its caller is not left waiting. */
        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            boolean wasStarted;
            synchronized (shutdownLock) {
                wasStarted = started;
            }
            future.complete(StorageResult.failure(StorageFailure.CLOSED, wasStarted
                    ? what + " (was already running when storage closed; its outcome is unknown)"
                    : what + " (cancelled by shutdown before it ran; nothing was written)"));
        }

        /** Answers a task the executor refused, which therefore never started. */
        private void cancelBeforeRun() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            future.complete(StorageResult.failure(StorageFailure.CLOSED, what));
        }
    }

    /** One storage operation, allowed to fail with the driver's exception type. */
    @FunctionalInterface
    private interface StorageOperation<T> {
        StorageResult<T> run() throws SQLException;
    }
    /** What runs on the worker after a task reported its outcome. */
    @FunctionalInterface
    private interface AfterOutcome {
        void run(StorageResult<?> result);
    }

    /** Whether a write creates a new claim or replaces an existing one. */
    private enum WriteMode {
        INSERT,
        UPDATE
    }

    /** A claims row with the database id that trusted rows reference. */
    private record IdRow(long id, ClaimCodec.Row row) {
    }
}
