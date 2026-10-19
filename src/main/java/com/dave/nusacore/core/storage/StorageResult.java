package com.dave.nusacore.core.storage;

import java.util.Objects;

/**
 * The outcome of a storage operation: a value, nothing, or a {@link StorageFailure}.
 *
 * <p>A failure is a normal value, not an exception. Expected answers such as a duplicate key or a missing claim are
 * returned here, so a call site cannot mistake a failure for success by ignoring a thrown exception.
 *
 * <p>{@link Done} is the success variant for an operation with no value. It is separate from {@link Success} so a
 * value-carrying success can never hold {@code null}.
 */
public sealed interface StorageResult<T>
        permits StorageResult.Success, StorageResult.Done, StorageResult.Failure {

    /** True when the operation completed. */
    default boolean ok() {
        return !(this instanceof Failure<?>);
    }

    /** True when the operation did not complete. */
    default boolean failed() {
        return this instanceof Failure<?>;
    }

    /** The value, or {@code null} when the operation failed or produced no value. */
    T valueOrNull();

    /** The failure, or {@code null} when the operation succeeded. */
    StorageFailure failureOrNull();

    /** A short context for the failure, empty when the operation succeeded. */
    default String detail() {
        return "";
    }

    /** The value, or {@code fallback} when the operation failed or produced no value. */
    default T orElse(T fallback) {
        T value = valueOrNull();
        return value == null ? fallback : value;
    }

    /** A successful operation with a value. */
    record Success<T>(T value) implements StorageResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public T valueOrNull() {
            return value;
        }

        @Override
        public StorageFailure failureOrNull() {
            return null;
        }
    }

    /** A successful operation with no value. */
    record Done() implements StorageResult<Void> {
        @Override
        public Void valueOrNull() {
            return null;
        }

        @Override
        public StorageFailure failureOrNull() {
            return null;
        }
    }

    /**
     * A failed operation.
     *
     * @param failure the reason, used for the decision
     * @param detail a short human-readable context for the log, never shown to players as-is
     */
    record Failure<T>(StorageFailure failure, String detail) implements StorageResult<T> {
        public Failure {
            Objects.requireNonNull(failure, "failure");
            detail = detail == null ? "" : detail;
        }

        @Override
        public T valueOrNull() {
            return null;
        }

        @Override
        public StorageFailure failureOrNull() {
            return failure;
        }

        @Override
        public String detail() {
            return detail;
        }
    }

    /** A successful result with a value. */
    static <T> StorageResult<T> success(T value) {
        return new Success<>(value);
    }

    /** A successful result with no value. */
    static StorageResult<Void> done() {
        return new Done();
    }

    /** A failed result with no extra detail. */
    static <T> StorageResult<T> failure(StorageFailure failure) {
        return new Failure<>(failure, "");
    }

    /** A failed result with a log detail. */
    static <T> StorageResult<T> failure(StorageFailure failure, String detail) {
        return new Failure<>(failure, detail);
    }
}
