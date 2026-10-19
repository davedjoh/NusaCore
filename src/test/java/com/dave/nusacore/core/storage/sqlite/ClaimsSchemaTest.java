package com.dave.nusacore.core.storage.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The schema itself, without the repository: the version it records, the constraints that mirror the domain, and the
 * claim key that keeps dimensions apart.
 */
class ClaimsSchemaTest {
    private static final String CREATED = "2026-01-02T03:04:05.000000000Z";

    @TempDir
    Path directory;

    @Test
    void applyingTheSchemaRecordsTheCurrentVersion() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            assertEquals(ClaimsSchema.CURRENT_VERSION, ClaimsSchema.readVersion(connection));
        }
    }

    @Test
    void applyingTwiceIsANoOp() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);
            ClaimsSchema.apply(connection);

            assertEquals(ClaimsSchema.CURRENT_VERSION, ClaimsSchema.readVersion(connection));
        }
    }

    @Test
    void aDatabaseFromANewerBuildIsRefused() throws Exception {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + (ClaimsSchema.CURRENT_VERSION + 1));
            }

            SQLException failure = assertThrows(SQLException.class, () -> ClaimsSchema.apply(connection));

            assertTrue(failure.getMessage().contains("newer"), () -> "unexpected message: " + failure.getMessage());
        }
    }

    @Test
    void theClaimKeyIsUniquePerDimensionAndChunk() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            insert(connection, "minecraft:overworld", 10, 20, "admin", null, null);
            // The same coordinates in another dimension are another claim, not a duplicate.
            insert(connection, "minecraft:the_nether", 10, 20, "admin", null, null);
            // Transposing x and z is a different chunk, so it must not collide with the row above.
            insert(connection, "minecraft:overworld", 20, 10, "admin", null, null);

            assertThrows(
                    SQLException.class,
                    () -> insert(connection, "minecraft:overworld", 10, 20, "admin", null, null),
                    "the same chunk in the same dimension must not be stored twice");
            assertThrows(
                    SQLException.class,
                    () -> insert(connection, "minecraft:the_nether", 10, 20, "admin", null, null),
                    "the duplicate rule applies within each dimension");
        }
    }

    @Test
    void theOwnerShapeIsEnforced() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            assertThrows(
                    SQLException.class,
                    () -> insert(connection, "minecraft:overworld", 1, 1, "player", null, null),
                    "a player claim needs an owner uuid");
            assertThrows(
                    SQLException.class,
                    () -> insert(connection, "minecraft:overworld", 1, 1, "admin", "11111111-2222-3333-4444-555555555555",
                            null),
                    "an admin claim must not carry an owner uuid");
            assertThrows(
                    SQLException.class,
                    () -> insert(connection, "minecraft:overworld", 1, 1, "guild", null, null),
                    "the owner kind is constrained to the two known kinds");

            insert(connection, "minecraft:overworld", 1, 1, "player", "11111111-2222-3333-4444-555555555555", null);
        }
    }

    @Test
    void anExpiryBeforeCreationIsRejected() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            assertThrows(
                    SQLException.class,
                    () -> insert(connection, "minecraft:overworld", 1, 1, "admin", null, "2026-01-02T03:04:04.999999999Z"),
                    "an expiry before the creation time is not a usable row");

            // An expiry equal to the creation time is allowed by the constraint; only an earlier one is refused.
            insert(connection, "minecraft:overworld", 1, 1, "admin", null, CREATED);
            insert(connection, "minecraft:overworld", 2, 2, "admin", null, "2027-01-01T00:00:00.000000000Z");
        }
    }

    /**
     * The schema enforces the ordering of a stored expiry against the creation time, so text comparison stays
     * chronological. It does not parse the timestamp itself: the column is text and the schema does not know the
     * format, so a malformed value is caught when the row is read, not when it is written.
     */
    @Test
    void aMalformedTimestampIsCaughtWhenReadNotWhenWritten() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            insert(connection, "minecraft:overworld", 1, 1, "admin", null, null);

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE claims SET created_at = 'yesterday'"));
            }
        }
    }

    @Test
    void aTrustedRowCannotOutliveItsClaim() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);
            insert(connection, "minecraft:overworld", 1, 1, "admin", null, null);
            long claimId;
            try (Statement statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id FROM claims")) {
                assertTrue(rows.next());
                claimId = rows.getLong(1);
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO claim_trusted (claim_id, player_uuid, permissions) VALUES (?, ?, ?)")) {
                insert.setLong(1, claimId);
                insert.setString(2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
                insert.setString(3, "build");
                assertEquals(1, insert.executeUpdate());
            }

            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM claims WHERE id = ?")) {
                delete.setLong(1, claimId);
                delete.executeUpdate();
            }

            try (Statement statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM claim_trusted")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void aTrustedRowForAMissingClaimIsRejected() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO claim_trusted (claim_id, player_uuid, permissions) VALUES (?, ?, ?)")) {
                insert.setLong(1, 999);
                insert.setString(2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
                insert.setString(3, "build");
                assertThrows(SQLException.class, insert::executeUpdate);
            }
        }
    }

    @Test
    void theClaimKeyIndexIsUnique() throws Exception {
        try (Connection connection = open()) {
            ClaimsSchema.apply(connection);

            try (Statement statement = connection.createStatement();
                    var rows = statement.executeQuery("PRAGMA index_list('claims')")) {
                boolean found = false;
                while (rows.next()) {
                    if ("claims_key".equals(rows.getString("name"))) {
                        found = true;
                        assertEquals(1, rows.getInt("unique"), "claims_key must be unique");
                    }
                }
                assertTrue(found, "the claim key index is missing");
            }
        }
    }

    private Connection open() throws SQLException {
        return SqliteDataSourceFactory.create(directory.resolve("claims.db")).getConnection();
    }

    private static void insert(
            Connection connection,
            String dimension,
            int chunkX,
            int chunkZ,
            String ownerKind,
            String ownerUuid,
            String expiresAt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO claims (dimension, chunk_x, chunk_z, owner_kind, owner_uuid, flags, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, '', ?, ?)")) {
            insert.setString(1, dimension);
            insert.setInt(2, chunkX);
            insert.setInt(3, chunkZ);
            insert.setString(4, ownerKind);
            if (ownerUuid == null) {
                insert.setNull(5, Types.VARCHAR);
            } else {
                insert.setString(5, ownerUuid);
            }
            insert.setString(6, CREATED);
            if (expiresAt == null) {
                insert.setNull(7, Types.VARCHAR);
            } else {
                insert.setString(7, expiresAt);
            }
            insert.executeUpdate();
        }
    }
}
