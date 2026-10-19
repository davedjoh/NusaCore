package com.dave.nusacore.core.storage.sqlite;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Builds the JDBC data source for one claim database file.
 *
 * <p>The pragmas are chosen for a small single-process server:
 * <ul>
 *   <li>{@code journal_mode=WAL}: a reader is not blocked by a writer, so a snapshot load can run while a write is in
 *       progress. SQLite creates a {@code -wal} file next to the database, which is expected.</li>
 *   <li>{@code synchronous=FULL}: a completed write is on disk before its future completes, which is what the
 *       commit-before-cache rule depends on.</li>
 *   <li>{@code foreign_keys=ON}: a trusted row can never outlive the claim it belongs to.</li>
 *   <li>{@code busy_timeout}: a brief lock waits instead of failing immediately.</li>
 *   <li>{@code transaction_mode=IMMEDIATE}: a write transaction takes the write lock when it starts, so two writers
 *       cannot both read and then write the same rows.</li>
 * </ul>
 *
 * <p>This class is the only place that names a JDBC URL or a pragma. Everything above it works with plain Java types.
 */
public final class SqliteDataSourceFactory {
    /** How long a statement waits for a lock before failing, in milliseconds. */
    public static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private SqliteDataSourceFactory() {
    }

    /** A configured source for one database file. */
    public static SQLiteDataSource create(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        return source;
    }
}
