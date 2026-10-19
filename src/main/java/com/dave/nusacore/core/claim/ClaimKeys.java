package com.dave.nusacore.core.claim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Builds a {@link ClaimKey} from a world position.
 *
 * <p>This is the only place the game's chunk arithmetic meets the claim model. The dimension is the level's
 * namespaced identifier, never folded into the chunk coordinates, so the same chunk in two dimensions stays two
 * distinct keys.
 */
public final class ClaimKeys {
    private ClaimKeys() {
    }

    /** The claim key covering {@code pos} in {@code level}. */
    public static ClaimKey at(Level level, BlockPos pos) {
        return new ClaimKey(
                level.dimension().location().toString(),
                pos.getX() >> 4,
                pos.getZ() >> 4);
    }

    /** The claim key covering an entity's current block position. */
    public static ClaimKey at(Entity entity) {
        return at(entity.level(), entity.blockPosition());
    }
}
