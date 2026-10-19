package com.dave.nusacore.server.storage;

import com.dave.nusacore.core.claim.ClaimStore;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageResult;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Server lifecycle for claim storage: start opening on server start, load the cache, close on server stop.
 *
 * <p>The world is not known until a server starts, and an integrated server can start more than once in one game
 * session, so storage is opened per server rather than during mod initialisation.
 *
 * <p>{@code SERVER_STARTING} runs on the server lifecycle thread, so the callback returns before any disk work
 * happens: creating the directory, opening the JDBC connection, running the schema pass, and reading the stored
 * claims all run on the repository's own storage worker. A server start therefore never waits on the disk, and a
 * broken database file cannot stall or fail the start. {@link ClaimStorageOpener} is the seam that keeps this class
 * testable without a server; {@link ClaimStorageOpener#sqlite()} is the production opener.
 *
 * <p>Readiness is explicit. {@link ClaimStorageSession#ready()} is false until the database opened, the schema pass
 * and the initial load have all succeeded, and gameplay reads must check it: a cache miss before that point means
 * "not loaded yet", not "unclaimed". A load failure is logged with its reason and leaves the session unready for the
 * whole session rather than presenting an empty world. A database that could not be opened reports the opening failure
 * itself, not only the load that was turned away because of it.
 *
 * <p>Stop order: storage closes on {@code SERVER_STOPPING}, while the server is still running. That close drains
 * accepted writes and is allowed to wait, because it is a shutdown path and nowhere else. It is the last lifecycle
 * step that may block; nothing waits on the disk at {@code SERVER_STARTING} or after {@code SERVER_STOPPING}. Even a
 * session left over from a start without a stop is closed in the background, so the start callback never drains.
 *
 * <p>A stop that happens while the open is still queueing on the storage worker is not a leak: the close drains the
 * worker, the initialisation and the load were already accepted, so both are answered and the worker exits.
 *
 * <p>Nothing uses this yet beyond opening it. Claim commands, protection, expiry policy, GUI, and currency are later
 * slices; this class exists so storage is durable and readable before they arrive.
 */
public final class ClaimStorageLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("nusacore");

    /** The database file, under the world's {@code data} directory, beside the world's other data files. */
    public static final String DATABASE_FILE_NAME = "nusacore-claims.db";

    /** The worker thread name, so a thread dump or a stall log names the database it serves. */
    static final String WORKER_THREAD_NAME = "nusacore-claim-storage";

    /**
     * How storage is opened. A seam rather than a plugin point: tests replace it to prove the lifecycle callback does
     * not wait on the disk, and nothing in the mod replaces it.
     */
    static volatile ClaimStorageOpener opener = ClaimStorageOpener.sqlite();

    /** The running session, or {@code null} before a server starts and after it stops. */
    private static volatile ClaimStorageSession session;

    /**
     * Where a session left over from a start without a stop is closed.
     *
     * <p>A normal restart closes storage on {@code SERVER_STOPPING}, so this executor is idle. It exists for the
     * abnormal case where a new start finds a session still open: draining that session is blocking, and the
     * lifecycle callback must not wait on it. One daemon thread is enough because the case is rare and the close
     * itself is bounded by the repository's drain timeout.
     */
    private static final ExecutorService LEAKED_SESSION_CLOSER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nusacore-claim-storage-reaper");
        thread.setDaemon(true);
        return thread;
    });

    private ClaimStorageLifecycle() {
    }

    /** Where one server's claim database lives. */
    public static Path databasePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATA).resolve(DATABASE_FILE_NAME);
    }

    /**
     * The open path with its collaborators passed in, so a test can prove what the lifecycle thread does and does not
     * wait for.
     *
     * <p>This method only starts work: it returns as soon as the opener returned, which is before the initialisation
     * and the load have finished. Readiness is reported separately by {@link ClaimStorageSession#ready()}.
     *
     * <p>Reporting is not attached here. {@link #watch(ClaimStorageSession, Executor)} runs after the caller published
     * the session, so a load that finishes immediately cannot be mistaken for a load from a replaced session.
     */
    static ClaimStorageSession openWith(ClaimStorageOpener storageOpener, Path database, String workerThreadName,
            Executor serverThread) {
        Objects.requireNonNull(storageOpener, "storageOpener");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(serverThread, "serverThread");

        ClaimStorageSession opened;
        try {
            opened = storageOpener.open(database, workerThreadName, serverThread);
        } catch (RuntimeException failure) {
            LOGGER.error("Claim storage could not be started for {}; claims are unavailable for this session",
                    database, failure);
            return null;
        }
        if (opened == null) {
            LOGGER.error("Claim storage could not be started for {}; claims are unavailable for this session",
                    database);
            return null;
        }

        LOGGER.info("Claim storage opening at {}; claims are not readable until the load finishes", database);
        return opened;
    }

    /**
     * Reports the initial load once the session is published.
     *
     * <p>Call after assigning {@link #session}, so {@link #reportLoad} sees the session it is reporting on. A load
     * that already finished is reported here on the calling thread rather than being dropped.
     */
    static void watch(ClaimStorageSession opened, Executor serverThread) {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(serverThread, "serverThread");
        opened.load().whenComplete((result, failure) -> deliver(opened, serverThread,
                () -> reportLoad(opened, result, failure)));
    }

    /** Reports on the server thread, or on the completing thread when the server is already stopping. */
    private static void deliver(ClaimStorageSession opened, Executor serverThread, Runnable report) {
        try {
            serverThread.execute(report);
        } catch (RejectedExecutionException stopping) {
            // The server is already stopping, so there is no server thread left to report on. Logging from the thread
            // that completed the load is better than dropping the outcome entirely.
            report.run();
        }
    }

    /**
     * Closes the current session, if any.
     *
     * <p>Idempotent, so a stop after a failed start, or a repeated stop event, is harmless. Clears the handle before
     * closing, so a read during shutdown sees no store rather than a closed one.
     */
    static void close() {
        ClaimStorageSession current = session;
        session = null;
        if (current == null) {
            return;
        }
        current.close();
        LOGGER.info("Claim storage closed with {} claim(s) cached", current.cache().size());
    }

    /**
     * The store for the running server, or empty when storage is not open.
     *
     * <p>The store is returned while it is still loading, because a command can report "storage is starting" rather
     * than "storage is unavailable". A gameplay read must check {@link #ready()} first; before it is true, a cache
     * miss means "not loaded yet", not "unclaimed".
     */
    public static Optional<ClaimStore> store() {
        ClaimStorageSession current = session;
        return current == null ? Optional.empty() : Optional.of(current.store());
    }

    /**
     * True when the database opened, the schema pass and the initial load all succeeded, so the cache is complete.
     *
     * <p>This is the gameplay gate. Until it is true, reads must not decide that a chunk is unclaimed.
     */
    public static boolean ready() {
        ClaimStorageSession current = session;
        return current != null && current.ready();
    }

    /** The session for the running server, or empty when storage is not open. */
    static Optional<ClaimStorageSession> session() {
        return Optional.ofNullable(session);
    }

    /** Registers the server lifecycle hooks. Called once from the server entrypoint. */
    public static void install() {
        ServerLifecycleEvents.SERVER_STARTING.register(ClaimStorageLifecycle::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(ClaimStorageLifecycle::onServerStopping);
    }

    /**
     * Publishes the session and returns. The {@code SERVER_STARTING} thread must not wait on the disk: everything the
     * open needs runs on the storage worker, and the load reports through the server thread.
     */
    private static void onServerStarting(MinecraftServer server) {
        start(databasePath(server), server::execute);
    }

    /**
     * The start path with its collaborators passed in, so a test can prove the lifecycle callback returns before a
     * blocked initialisation and that readiness follows the asynchronous load.
     *
     * <p>Returns after the opener returned and the session was published. The schema pass and the initial load may
     * still be running; {@link ClaimStorageSession#ready()} is the only signal that gameplay may treat a cache miss as
     * unclaimed.
     *
     * <p>A session left over from a start without a stop is closed on {@link #LEAKED_SESSION_CLOSER} rather than
     * here. Closing drains accepted writes, so doing it on this thread would reintroduce the stall this method exists
     * to avoid.
     */
    static ClaimStorageSession start(Path database, Executor serverThread) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(serverThread, "serverThread");
        closeLeakedSession();
        ClaimStorageSession opened = openWith(opener, database, WORKER_THREAD_NAME, serverThread);
        session = opened;
        if (opened != null) {
            // Attached after publishing, so a load that already finished is reported rather than seen as stale.
            watch(opened, serverThread);
        }
        return opened;
    }

    /** Closes a session left behind by a start without a stop, without waiting on its drain. */
    private static void closeLeakedSession() {
        ClaimStorageSession current = session;
        if (current == null) {
            return;
        }
        session = null;
        LOGGER.warn("Claim storage from a previous server start was still open; closing it in the background");
        try {
            LEAKED_SESSION_CLOSER.execute(current::close);
        } catch (RejectedExecutionException rejected) {
            // The JVM is already shutting down. The worker is a daemon, so the process can still exit.
            LOGGER.warn("Could not schedule the previous claim storage close; the worker will end with the JVM",
                    rejected);
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        close();
    }

    private static void reportLoad(ClaimStorageSession opened,
            StorageResult<ClaimRepository.ClaimSnapshot> result, Throwable failure) {
        Path database = opened.database();
        ClaimStorageSession current = session;
        if (current != null && current != opened) {
            LOGGER.warn("Claim load from {} finished after this session was replaced; its result is ignored",
                    database);
            return;
        }
        if (failure != null) {
            LOGGER.error("Claim cache could not be loaded from {}: {}", database, failure);
            return;
        }
        if (result == null) {
            LOGGER.error("Claim load from {} reported neither a result nor a failure", database);
            return;
        }
        if (result.failed()) {
            StorageResult<Path> opening = opened.openResult();
            if (opening != null && opening.failed()) {
                LOGGER.error("Claim cache could not be loaded from {}: storage did not open ({} {}); claims stay"
                        + " unavailable for this session", database, opening.failureOrNull(), opening.detail());
                return;
            }
            LOGGER.error("Claim cache could not be loaded from {}: {} {}; claims stay unavailable for this session",
                    database, result.failureOrNull(), result.detail());
            return;
        }
        if (!opened.ready()) {
            LOGGER.error("Claim storage at {} reported a load that did not make it ready; claims stay unavailable",
                    database);
            return;
        }
        ClaimRepository.ClaimSnapshot snapshot = result.valueOrNull();
        if (snapshot.complete()) {
            LOGGER.info("Claim cache loaded {} claim(s) and is ready", snapshot.claims().size());
            return;
        }
        LOGGER.warn("Claim cache loaded {} claim(s) and skipped {} unreadable row(s); the skipped rows stay in the"
                + " database and need attention", snapshot.claims().size(), snapshot.unreadableRows().size());
        for (String row : snapshot.unreadableRows()) {
            LOGGER.warn("Skipped claim row: {}", row);
        }
    }
}
