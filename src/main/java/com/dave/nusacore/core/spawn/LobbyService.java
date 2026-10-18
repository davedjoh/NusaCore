package com.dave.nusacore.core.spawn;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.message.Messages;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Pure lobby logic: resolves a safe destination at the world spawn and describes the outcome.
 *
 * <p>The spawn is read from the surface on every call, so {@code /setworldspawn} and world border changes are
 * honored without a copy, a config value, or a database. Nothing here writes respawn data, so first join and
 * bed or anchor respawn behavior is untouched. All work is in-memory: loaded chunks only, no disk or database
 * access.
 */
public final class LobbyService {
    private final SpawnConfig config;

    public LobbyService(SpawnConfig config) {
        this.config = config;
    }

    /** Result of a lobby request. A failed outcome means the player must not move. */
    public record Outcome(Status status, BlockPos destination) {
        public enum Status {
            READY,
            NO_OVERWORLD,
            NO_SAFE_DESTINATION
        }

        public boolean ready() {
            return status == Status.READY;
        }
    }

    /**
     * Resolves the destination for one lobby request.
     *
     * @param surface the Overworld view, or {@code null} when the Overworld is not available
     */
    public Outcome resolve(SpawnSurface surface) {
        if (surface == null) {
            return new Outcome(Outcome.Status.NO_OVERWORLD, null);
        }
        SafeSpawnSearch search = new SafeSpawnSearch(config.horizontalSearchRadius(), config.scanDepth());
        return SafeDestination.find(surface, surface.worldSpawn(), search)
                .map(ground -> new Outcome(Outcome.Status.READY, SafeDestination.plankPosition(ground)))
                .orElseGet(() -> new Outcome(Outcome.Status.NO_SAFE_DESTINATION, null));
    }

    /** Message for an outcome. */
    public static Component message(Outcome outcome) {
        return switch (outcome.status()) {
            case READY -> Messages.lobbySuccess();
            case NO_OVERWORLD -> Messages.lobbyNoOverworld();
            case NO_SAFE_DESTINATION -> Messages.lobbyNoSafeDestination();
        };
    }

    /** Center of the destination block, at the feet height used by mob spawning on the same ground. */
    public static Vec3 feetPosition(BlockPos destination) {
        return new Vec3(
                destination.getX() + 0.5,
                SpawnSurface.feetY(destination.below()),
                destination.getZ() + 0.5);
    }
}
