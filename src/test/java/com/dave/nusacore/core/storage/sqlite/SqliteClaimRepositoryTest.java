package com.dave.nusacore.core.storage.sqlite;

import com.dave.nusacore.core.claim.Claim;
import com.dave.nusacore.core.claim.ClaimFlags;
import com.dave.nusacore.core.claim.ClaimKey;
import com.dave.nusacore.core.claim.ClaimOwner;
import com.dave.nusacore.core.claim.TrustedPermission;
import com.dave.nusacore.core.storage.ClaimRepository;
import com.dave.nusacore.core.storage.StorageFailure;
import com.dave.nusacore.core.storage.StorageResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SQLite repository against a real database file: what survives a write and a restart, what a duplicate or a
 * missing claim reports, and that nothing runs on the calling thread.
 */
class SqliteClaimRepositoryTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID FRIEND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final String WORKER = "nusacore-claim-storage-test";

    @TempDir
    Path directory;

    @Test
    void openingCreatesTheFileTheDirectoryAndTheCurrentSchema() throws Exception {
        Path file = directory.resolve("nested").resolve("data").resolve("nusacore-claims.db");

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(file, WORKER)) {
            assertTrue(java.nio.file.Files.isRegularFile(file), "opening must create the database file");

            try (Connection connection = SqliteDataSourceFactory.create(file).getConnection()) {
                assertEquals(ClaimsSchema.CURRENT_VERSION, ClaimsSchema.readVersion(connection));
            }
        }
    }

    @Test
    void aPlayerClaimWithTrustAndExpirySurvivesARestart() throws Exception {
        Claim claim = new Claim(
                new ClaimKey("minecraft:overworld", -12, 34),
                ClaimOwner.player(OWNER),
                Map.of(FRIEND, Set.of(TrustedPermission.BUILD, TrustedPermission.CONTAINERS)),
                ClaimFlags.NONE,
                CREATED,
                Instant.parse("2026-06-01T12:00:00Z"));
        Path file = directory.resolve("claims.db");

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(file, WORKER)) {
            assertTrue(await(repository.create(claim)).ok());
        }

        try (SqliteClaimRepository reopened = SqliteClaimRepository.open(file, WORKER)) {
            assertEquals(claim, await(reopened.find(claim.key())).valueOrNull());
        }
    }

    @Test
    void aClaimWithoutAnExpiryKeepsANullExpiry() throws Exception {
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 0, 0), ClaimOwner.ADMIN, CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            assertTrue(await(repository.create(claim)).ok());

            Claim read = await(repository.find(claim.key())).valueOrNull();

            assertNotNull(read);
            assertNull(read.expiresAt(), "storage must not invent an expiry");
            assertFalse(read.playerOwned());
        }
    }

    @Test
    void theSameChunkInTwoDimensionsIsTwoClaims() throws Exception {
        Claim overworld = Claim.created(new ClaimKey("minecraft:overworld", 7, 7), ClaimOwner.player(OWNER), CREATED);
        Claim nether = Claim.created(new ClaimKey("minecraft:the_nether", 7, 7), ClaimOwner.ADMIN, CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            assertTrue(await(repository.create(overworld)).ok());
            assertTrue(await(repository.create(nether)).ok());

            assertEquals(overworld, await(repository.find(overworld.key())).valueOrNull());
            assertEquals(nether, await(repository.find(nether.key())).valueOrNull());
            assertEquals(2, await(repository.loadAll()).valueOrNull().claims().size());
        }
    }

    @Test
    void aDuplicateKeyIsReportedAsANormalFailure() throws Exception {
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 1, 1), ClaimOwner.player(OWNER), CREATED);
        Claim competitor = Claim.created(
                new ClaimKey("minecraft:overworld", 1, 1), ClaimOwner.player(UUID.randomUUID()), CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            assertTrue(await(repository.create(claim)).ok());

            StorageResult<Void> second = await(repository.create(competitor));

            assertEquals(StorageFailure.DUPLICATE_KEY, second.failureOrNull());
            assertEquals(claim, await(repository.find(claim.key())).valueOrNull(), "the first claim must survive");
        }
    }

    @Test
    void updatingReplacesTheClaimAndItsTrustList() throws Exception {
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 2, 2), ClaimOwner.player(OWNER), CREATED)
                .withTrust(FRIEND, Set.of(TrustedPermission.BUILD));
        Claim updated = claim.withTrust(FRIEND, Set.of(TrustedPermission.USE))
                .withTrust(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), Set.of(TrustedPermission.BUILD))
                .withExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            assertTrue(await(repository.create(claim)).ok());
            assertTrue(await(repository.update(updated)).ok());

            assertEquals(updated, await(repository.find(claim.key())).valueOrNull());
            assertEquals(1, await(repository.loadAll()).valueOrNull().claims().size());
        }
    }

    @Test
    void updatingAMissingClaimReportsNotFound() throws Exception {
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 3, 3), ClaimOwner.player(OWNER), CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            StorageResult<Void> result = await(repository.update(claim));

            assertEquals(StorageFailure.NOT_FOUND, result.failureOrNull());
            assertEquals(StorageFailure.NOT_FOUND, await(repository.find(claim.key())).failureOrNull());
        }
    }

    @Test
    void deletingRemovesTheClaimAndItsTrustedRows() throws Exception {
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 4, 4), ClaimOwner.player(OWNER), CREATED)
                .withTrust(FRIEND, Set.of(TrustedPermission.BUILD));
        Path file = directory.resolve("claims.db");

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(file, WORKER)) {
            assertTrue(await(repository.create(claim)).ok());
            assertTrue(await(repository.delete(claim.key())).ok());

            assertEquals(StorageFailure.NOT_FOUND, await(repository.find(claim.key())).failureOrNull());
            assertEquals(StorageFailure.NOT_FOUND, await(repository.delete(claim.key())).failureOrNull());
        }

        try (Connection connection = SqliteDataSourceFactory.create(file).getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM claim_trusted")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "trusted rows must not outlive their claim");
        }
    }

    @Test
    void loadingOrdersClaimsByDimensionAndChunk() throws Exception {
        Claim nether = Claim.created(new ClaimKey("minecraft:the_nether", 0, 0), ClaimOwner.ADMIN, CREATED);
        Claim overworldEast = Claim.created(new ClaimKey("minecraft:overworld", 9, 0), ClaimOwner.ADMIN, CREATED);
        Claim overworldWest = Claim.created(new ClaimKey("minecraft:overworld", -9, 0), ClaimOwner.ADMIN, CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            assertTrue(await(repository.create(overworldEast)).ok());
            assertTrue(await(repository.create(nether)).ok());
            assertTrue(await(repository.create(overworldWest)).ok());

            ClaimRepository.ClaimSnapshot snapshot = await(repository.loadAll()).valueOrNull();

            assertNotNull(snapshot);
            assertEquals(
                    List.of(overworldWest.key(), overworldEast.key(), nether.key()),
                    snapshot.claims().stream().map(Claim::key).toList());
            assertTrue(snapshot.complete());
        }
    }

    @Test
    void anEmptyDatabaseLoadsEmptyAndComplete() throws Exception {
        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            StorageResult<ClaimRepository.ClaimSnapshot> result = await(repository.loadAll());

            assertTrue(result.ok());
            assertTrue(result.valueOrNull().claims().isEmpty());
            assertTrue(result.valueOrNull().complete());
        }
    }

    @Test
    void anUnreadableRowIsReportedInsteadOfFailingTheWholeLoad() throws Exception {
        Path file = directory.resolve("claims.db");
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 0, 0), ClaimOwner.ADMIN, CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(file, WORKER)) {
            assertTrue(await(repository.create(claim)).ok());
        }

        // A row whose stored timestamp is not in the stored format could only appear through a bug or manual edit.
        try (Connection connection = SqliteDataSourceFactory.create(file).getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE claims SET created_at = 'yesterday'");
        }

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(file, WORKER)) {
            StorageResult<ClaimRepository.ClaimSnapshot> result = await(repository.loadAll());

            assertTrue(result.ok(), "one bad row must not fail the load");
            assertTrue(result.valueOrNull().claims().isEmpty());
            assertFalse(result.valueOrNull().complete());
            assertEquals(1, result.valueOrNull().unreadableRows().size());
            assertEquals(
                    StorageFailure.CORRUPT_ROW, await(repository.find(claim.key())).failureOrNull());
        }
    }

    @Test
    void workRunsOffTheCallingThreadAndCompletesWithoutThrowing() throws Exception {
        Claim claim = Claim.created(new ClaimKey("minecraft:overworld", 5, 5), ClaimOwner.player(OWNER), CREATED);

        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            CompletableFuture<StorageResult<Void>> created = repository.create(claim);
            CompletableFuture<StorageResult<Claim>> found = repository.find(claim.key());
            CompletableFuture<StorageResult<ClaimRepository.ClaimSnapshot>> loaded = repository.loadAll();

            assertTrue(created.join().ok());
            assertTrue(found.join().ok());
            assertTrue(loaded.join().ok());
            assertEquals(claim, found.join().valueOrNull());
        }
    }

    @Test
    void aClosedRepositoryReportsClosedRatherThanThrowing() throws Exception {
        SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER);
        repository.close();
        repository.close();

        assertTrue(repository.isClosed());
        assertEquals(StorageFailure.CLOSED, await(repository.loadAll()).failureOrNull());
        assertEquals(
                StorageFailure.CLOSED,
                await(repository.find(new ClaimKey("minecraft:overworld", 0, 0))).failureOrNull());
        assertEquals(
                StorageFailure.CLOSED,
                await(repository.create(
                                Claim.created(new ClaimKey("minecraft:overworld", 0, 0), ClaimOwner.ADMIN, CREATED)))
                        .failureOrNull());
    }

    @Test
    void theWorkerThreadIsNamedAndDaemon() throws Exception {
        try (SqliteClaimRepository repository = SqliteClaimRepository.open(directory.resolve("claims.db"), WORKER)) {
            // The only thread that runs storage work is the repository's own daemon worker, named after the database.
            java.lang.reflect.Field field = SqliteClaimRepository.class.getDeclaredField("executor");
            field.setAccessible(true);
            java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) field.get(repository);
            Thread worker = CompletableFuture.supplyAsync(Thread::currentThread, executor).get(10, TimeUnit.SECONDS);

            assertEquals(WORKER, worker.getName());
            assertTrue(worker.isDaemon(), "the storage worker must not keep the JVM alive");
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

    @Test
    void thePragmasAreApplied() throws Exception {
        Path file = directory.resolve("claims.db");
        try (SqliteClaimRepository ignored = SqliteClaimRepository.open(file, WORKER)) {
            // Opening runs the schema pass on a configured connection, so the file's journal mode is already WAL.
            try (Connection connection = SqliteDataSourceFactory.create(file).getConnection();
                    var statement = connection.createStatement()) {
                try (var journal = statement.executeQuery("PRAGMA journal_mode")) {
                    assertTrue(journal.next());
                    assertEquals("wal", journal.getString(1).toLowerCase());
                }
                try (var foreignKeys = statement.executeQuery("PRAGMA foreign_keys")) {
                    assertTrue(foreignKeys.next());
                    assertEquals(1, foreignKeys.getInt(1));
                }
                try (var synchronous = statement.executeQuery("PRAGMA synchronous")) {
                    assertTrue(synchronous.next());
                    assertEquals(2, synchronous.getInt(1), "2 is FULL");
                }
            }
        }
    }
}
