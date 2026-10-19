package com.dave.nusacore.core.claim;

import java.util.Objects;

/**
 * The outcome of a claim operation, as a message key and its arguments.
 *
 * <p>Commands never invent text: they render this through
 * {@link com.dave.nusacore.core.message.MessageText}. Keeping the key here means a service test can assert the
 * decision without building a component or a command source.
 *
 * @param ok whether the operation changed durable state
 * @param messageKey the key in {@link com.dave.nusacore.core.message.Messages}
 * @param arguments values for the key's placeholders
 */
public record ClaimResult(boolean ok, String messageKey, Object[] arguments) {
    public ClaimResult {
        Objects.requireNonNull(messageKey, "messageKey");
        arguments = arguments == null ? new Object[0] : arguments.clone();
    }

    public static ClaimResult success(String messageKey, Object... arguments) {
        return new ClaimResult(true, messageKey, arguments);
    }

    public static ClaimResult failure(String messageKey, Object... arguments) {
        return new ClaimResult(false, messageKey, arguments);
    }
}
