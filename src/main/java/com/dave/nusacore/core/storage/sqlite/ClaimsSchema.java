package com.dave.nusacore.core.storage.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The claims schema, versioned and applied in order.
 *
 * <p>The schema is created and migrated by this mod, not by an external tool. Migrations only ever grow: a released
 * migration is never edited, because a server that already applied it would never see the change. The applied
 * version lives in {@code PRAGMA user_version}, which SQLite keeps in the database header and updates in the same
 * transaction as the schema change.
 *
 * <p>Claim identity is the unique {@code (dimension, chunk_x, chunk_z)} triple, not a packed chunk coordinate. Every
 * row names its dimension, so a lookup can never return another dimension's chunk.
 *
 * <p>The constraints mirror the domain types, so the database rejects a row the code could not read:
 * <ul>
 *   <li>{@code owner_kind} is {@code 'player'} with an owner UUID, or {@code 'admin'} with no owner UUID.</li>
 *   <li>{@code expires_at} is nullable by design. No expiry policy exists yet, so it stays empty until one is
 *       decided.</li>
 *   <li>a trusted row references its claim and is removed with it.</li>
 * </ul>
 */
public final class ClaimsSchema {
    /** The version this build writes and expects. Bump it only together with a new migration below. */
    public static final int CURRENT_VERSION = 1;

    /** Table holding one row per claim. */
    public static final String CLAIMS_TABLE = "claims";

    /** Table holding one row per trusted player of a claim. */
    public static final String CLAIM_TRUSTED_TABLE = "claim_trusted";

    /**
     * Statements that migrate version {@code targetVersion - 1} to {@code targetVersion}. Each statement is executed
     * on its own, so a migration never depends on the driver splitting a script.
     */
    private static final List<List<String>> MIGRATIONS = List.of(
            // Version 1: the initial claim schema.
            List.of(
                    """
                    CREATE TABLE claims (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        dimension   TEXT    NOT NULL CHECK (length(dimension) BETWEEN 1 AND 128),
                        chunk_x     INTEGER NOT NULL,
                        chunk_z     INTEGER NOT NULL,
                        owner_kind  TEXT    NOT NULL CHECK (owner_kind IN ('player', 'admin')),
                        owner_uuid  TEXT             CHECK (owner_uuid IS NULL OR length(owner_uuid) = 36),
                        flags       TEXT    NOT NULL DEFAULT '',
                        created_at  TEXT    NOT NULL,
                        expires_at  TEXT,
                        CHECK (
                            (owner_kind = 'player' AND owner_uuid IS NOT NULL)
                            OR (owner_kind = 'admin' AND owner_uuid IS NULL)
                        ),
                        CHECK (expires_at IS NULL OR expires_at >= created_at)
                    )
                    """,
                    "CREATE UNIQUE INDEX claims_key ON claims (dimension, chunk_x, chunk_z)",
                    "CREATE INDEX claims_owner ON claims (owner_kind, owner_uuid)",
                    "CREATE INDEX claims_expiry ON claims (expires_at) WHERE expires_at IS NOT NULL",
                    """
                    CREATE TABLE claim_trusted (
                        claim_id    INTEGER NOT NULL REFERENCES claims (id) ON DELETE CASCADE,
                        player_uuid TEXT    NOT NULL CHECK (length(player_uuid) = 36),
                        permissions TEXT    NOT NULL DEFAULT '',
                        PRIMARY KEY (claim_id, player_uuid)
                    )
                    """));

    private ClaimsSchema() {
    }

    /** The statements for one target version, for tests and for {@link #apply}. */
    public static List<String> migration(int targetVersion) {
        if (targetVersion < 1 || targetVersion > CURRENT_VERSION) {
            throw new IllegalArgumentException("no migration to version " + targetVersion);
        }
        return MIGRATIONS.get(targetVersion - 1);
    }

    /**
     * Brings a database up to {@link #CURRENT_VERSION}.
     *
     * <p>Each version is applied inside one transaction together with its version bump, so a crash leaves either the
     * old schema and the old version or the new schema and the new version, never a half-migrated database.
     *
     * @throws SQLException when the database cannot be read or written, or when it is newer than this build
     */
    public static void apply(Connection connection) throws SQLException {
        int version = readVersion(connection);
        if (version > CURRENT_VERSION) {
            throw new SQLException("claim database schema version " + version
                    + " is newer than this build supports (" + CURRENT_VERSION + ")");
        }
        if (version == CURRENT_VERSION) {
            return;
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (int target = version + 1; target <= CURRENT_VERSION; target++) {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration(target)) {
                        statement.execute(sql);
                    }
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + CURRENT_VERSION);
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** The schema version recorded in the database header. */
    public static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("PRAGMA user_version")) {
            if (!row.next()) {
                throw new SQLException("could not read PRAGMA user_version");
            }
            return row.getInt(1);
        }
    }
}
