package com.dave.nusacore.server.claim;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves a trust target without blocking the game thread.
 *
 * <p>An explicit UUID is used as given. An online player is read from the player list, which is an in-memory lookup.
 * Anything else goes to the server's profile cache on a separate daemon thread, because
 * {@code UserNameToIdResolver#get(String)} can perform a blocking profile lookup for a name it has never seen. The
 * cache is still used first by that resolver, so a known offline player resolves without a network call.
 *
 * <p>The lookup thread is a daemon and is shared by the whole process. Nothing on the game thread waits for it.
 */
public final class PlayerIdentityResolver {
    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nusacore-profile-lookup");
        thread.setDaemon(true);
        return thread;
    });

    private PlayerIdentityResolver() {
    }

    /**
     * Resolves a name or UUID.
     *
     * @param server the running server, or {@code null} in a test with no game
     * @param input a player name or a UUID in its canonical form
     * @return the UUID, or empty when the name is unknown or the lookup failed
     */
    public static CompletableFuture<Optional<UUID>> resolve(MinecraftServer server, String input) {
        if (input == null || input.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String trimmed = input.trim();
        Optional<UUID> explicit = parseUuid(trimmed);
        if (explicit.isPresent()) {
            return CompletableFuture.completedFuture(explicit);
        }
        if (server == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(trimmed);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUUID()));
        }
        return CompletableFuture.supplyAsync(() -> server.services().nameToIdCache()
                .get(trimmed)
                .map(NameAndId::id), LOOKUP);
    }

    private static Optional<UUID> parseUuid(String input) {
        if (input.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
