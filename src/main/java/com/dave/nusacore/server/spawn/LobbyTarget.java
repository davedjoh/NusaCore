package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.spawn.SpawnSurface;

import net.minecraft.core.BlockPos;

/**
 * The Overworld as the {@code /lobby} destination: the read-only view the safety rules inspect, and the move
 * into that same level.
 *
 * <p>Both operations belong to one object on purpose. A destination position is only meaningful in the level it
 * was read from, so resolving through {@link #surface()} and then moving through {@link #teleport} can never mix
 * coordinates from the player's current dimension with the Overworld. The player is the type parameter because a
 * live {@code ServerPlayer} cannot be built without a running server, so the request path is testable with a
 * token player.
 */
public interface LobbyTarget<P> {

    /** The level's blocks, limited to already loaded chunks. */
    SpawnSurface surface();

    /**
     * Moves the player to the destination block, switching dimension when the player is elsewhere.
     *
     * @param player the executing player
     * @param destination the block the player stands in, read from {@link #surface()}
     * @return true when the server accepted the move, false when the player could not be moved
     */
    boolean teleport(P player, BlockPos destination);
}
