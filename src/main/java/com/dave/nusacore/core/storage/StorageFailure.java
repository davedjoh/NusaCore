package com.dave.nusacore.core.storage;

/**
 * Why a storage operation did not succeed.
 *
 * <p>The reasons are distinct because callers must react differently: a duplicate key is a normal answer to a claim
 * request, while an unreadable row is a data problem an administrator has to see. Every reason maps to one result
 * variant, so a caller never has to inspect an exception to tell them apart.
 *
 * <p>None of these is thrown. A driver failure is wrapped once at the storage boundary and reported as
 * {@link #FAILURE}; the exception is kept for the log, not for the decision.
 */
public enum StorageFailure {
    /** The claim key is already taken. Nothing was written. */
    DUPLICATE_KEY("the claim key already exists"),

    /** The claim to save does not exist. Nothing was written. */
    NOT_FOUND("the claim does not exist"),

    /** A stored row is present but cannot be read as a claim. It is reported and skipped, never guessed at. */
    CORRUPT_ROW("a stored claim row cannot be read"),

    /** The storage call failed. The durable state is unchanged as far as the caller can tell. */
    FAILURE("the storage operation failed"),

    /** Storage is closed, or a call was made after the server started stopping. */
    CLOSED("storage is closed");

    private final String description;

    StorageFailure(String description) {
        this.description = description;
    }

    /** A short English description for logs and messages. */
    public String description() {
        return description;
    }
}
